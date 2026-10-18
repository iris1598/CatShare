package moe.reimu.catshare.utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.BuildConfig
import moe.reimu.catshare.IMacAddressService
import moe.reimu.catshare.services.MacAddressService
import rikka.shizuku.Shizuku
import java.net.NetworkInterface
import kotlin.collections.iterator

object ShizukuUtils {
    /**
     * Dedicated logcat tag for the MAC address resolution, so the runtime value can be observed
     * with `adb logcat -s CatShareMac`.
     */
    const val MAC_LOG_TAG = "CatShareMac"

    private const val LOCAL_MAC_ADDRESS_PERMISSION = "android.permission.LOCAL_MAC_ADDRESS"

    private val binderLock = Object()
    private val serviceNotify = Object()
    private var macService: IMacAddressService? = null

    init {
        Shizuku.addBinderReceivedListenerSticky {
            bindService()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder?) {
            if (service != null && service.pingBinder()) {
                Log.d(ShizukuUtils.TAG, "Got service connection for $name")

                synchronized(binderLock) {
                    macService = IMacAddressService.Stub.asInterface(service)
                }

                synchronized(serviceNotify) {
                    serviceNotify.notifyAll()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(ShizukuUtils.TAG, "Connection lost for $name")
            synchronized(binderLock) {
                macService = null
            }
        }
    }

    fun unsafeBindService() {
        val cn = ComponentName(
            BuildConfig.APPLICATION_ID, MacAddressService::class.java.name
        )
        val args = Shizuku.UserServiceArgs(cn)
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        Shizuku.bindUserService(args, serviceConnection)
    }

    fun bindService() {
        try {
            unsafeBindService()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind service", e)
        }
    }

    fun unsafeGetMacAddress(name: String): String? {
        synchronized(binderLock) {
            val svc = macService
            if (svc == null) {
                Log.d(TAG, "MAC service is null, trying to bind")
                unsafeBindService()
            } else {
                return svc.getMacAddressByName(name)
            }
        }

        synchronized(serviceNotify) {
            serviceNotify.wait(1000 * 20)
        }

        synchronized(binderLock) {
            return macService?.p2pMacAddress
        }
    }

    /**
     * Resolve the MAC address of a network interface using the following priority:
     *
     *  1. a manually configured MAC address from Settings, if present;
     *  2. root (no Shizuku needed, see [RootUtils]);
     *  3. the `LOCAL_MAC_ADDRESS` permission (pre-installed system apps only);
     *  4. Shizuku, when installed and granted;
     *  5. the native [NetworkInterface] fallback (returns null for normal apps on modern Android,
     *     kept as a last resort for older devices).
     *
     * The returned [RootUtils.MacReadResult] contains the source and a reliability flag so the
     * Settings UI can show where the value came from.
     *
     * This is a blocking call, do not invoke it on the main thread.
     */
    fun resolveMacAddress(
        context: Context,
        name: String = RootUtils.P2P_INTERFACE_NAME,
    ): RootUtils.MacReadResult {
        // 1. Manual override.
        RootUtils.normalizeMac(AppSettings(context).macAddress)?.let { manualMac ->
            Log.i(TAG, "Using manually configured MAC address for $name: $manualMac")
            return RootUtils.MacReadResult(manualMac, "manual override", true)
        }

        // 2. Root, preferred whenever available: it works without Shizuku and without the
        //    LOCAL_MAC_ADDRESS permission.
        val rootResult = try {
            RootUtils.readMacAddress(name)
        } catch (e: Throwable) {
            Log.e(TAG, "Root based MAC detection failed for $name", e)
            RootUtils.MacReadResult(null, "root error", false)
        }
        if (rootResult.mac != null) {
            Log.i(
                TAG,
                "Using root MAC address for $name: ${rootResult.mac} " +
                        "(source: ${rootResult.source}, reliable: ${rootResult.reliable})"
            )
            return RootUtils.MacReadResult(rootResult.mac, "root: ${rootResult.source}", rootResult.reliable)
        }
        Log.d(TAG, "Root did not provide a MAC address for $name, falling back")

        // 3. LOCAL_MAC_ADDRESS permission (pre-installed system apps only).
        if (context.checkSelfPermission(LOCAL_MAC_ADDRESS_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            nativeGetMacAddressByName(name)?.let { nativeMac ->
                Log.i(TAG, "Using native MAC address (LOCAL_MAC_ADDRESS) for $name: $nativeMac")
                return RootUtils.MacReadResult(nativeMac, "LOCAL_MAC_ADDRESS permission", true)
            }
        }

        // 4. Shizuku, optional. Skip it when the binder is missing or not granted so we never
        //    block for 20 seconds on devices without Shizuku.
        val shizukuMac = try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Trying to obtain MAC address for $name via Shizuku")
                unsafeGetMacAddress(name)?.let { RootUtils.normalizeMac(it) }
            } else {
                Log.d(TAG, "Shizuku is not available or not granted, skipping")
                null
            }
        } catch (e: Throwable) {
            Log.e(ShizukuUtils.TAG, "Failed to obtain MAC address for $name via Shizuku", e)
            null
        }
        if (shizukuMac != null) {
            Log.i(TAG, "Using Shizuku MAC address for $name: $shizukuMac")
            return RootUtils.MacReadResult(shizukuMac, "Shizuku user service", true)
        }

        // 5. Native fallback.
        val nativeMac = try {
            nativeGetMacAddressByName(name)
        } catch (e: Throwable) {
            Log.e(ShizukuUtils.TAG, "Failed to obtain MAC address for $name natively", e)
            null
        }
        if (nativeMac != null) {
            Log.i(TAG, "Using native network interface MAC address for $name: $nativeMac")
            return RootUtils.MacReadResult(nativeMac, "native NetworkInterface", true)
        }

        Log.w(TAG, "Could not determine a MAC address for $name")
        return RootUtils.MacReadResult(null, "not detected", false)
    }

    /**
     * Obtain the MAC address of a network interface (see [resolveMacAddress] for the priority
     * order). The callback is always invoked from a worker thread.
     *
     * The result is logged with the [MAC_LOG_TAG] tag: `adb logcat -s CatShareMac`.
     */
    fun getMacAddress(context: Context, name: String, l: (String?) -> Unit) {
        val th = Thread {
            val result = try {
                resolveMacAddress(context, name)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to resolve MAC address for $name", e)
                RootUtils.MacReadResult(null, "error", false)
            }

            if (result.mac != null) {
                Log.i(
                    MAC_LOG_TAG,
                    "Resolved $name MAC address: ${result.mac} " +
                            "(source: ${result.source}, reliable: ${result.reliable})"
                )
            } else {
                Log.w(
                    MAC_LOG_TAG,
                    "Failed to resolve $name MAC address (source: ${result.source})"
                )
            }
            l(result.mac)
        }
        th.isDaemon = true
        th.start()
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun nativeGetMacAddressByName(name: String): String? {
        val ifs = NetworkInterface.getNetworkInterfaces()
        for (intf in ifs) {
            if (intf.name == name) {
                return intf.hardwareAddress?.toHexString(HexFormat {
                    bytes.byteSeparator = ":"
                })
            }
        }
        return null
    }

    suspend fun getMacAddress(context: Context, name: String): String? {
        val fut = CompletableDeferred<String?>()
        getMacAddress(context, name) {
            fut.complete(it)
        }
        return fut.await()
    }
}
