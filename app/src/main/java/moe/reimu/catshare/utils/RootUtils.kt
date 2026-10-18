package moe.reimu.catshare.utils

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Root based helper used to obtain the device's Wi-Fi Direct (P2P) MAC address without Shizuku.
 *
 * Why root: since Android 10 the hardware address of network interfaces is hidden from normal
 * apps. Reading `/sys/class/net/<iface>/address` (or `ip link`) requires root, and the
 * `LOCAL_MAC_ADDRESS` permission is only granted to system apps. On a rooted device we can
 * simply ask `su` for it.
 *
 * The P2P device address advertised by the framework (`WifiP2pDevice.deviceAddress`) is the value
 * peers use to identify this device. Verified on a Redmi 13C / Android 15 / KernelSU:
 *
 *     wpa_supplicant p2p_device_persistent_mac_addr: 3a:08:c7:78:88:67
 *     WifiP2pDevice.deviceAddress (cmd wifip2p get-device-info): 3a:08:c7:78:88:67
 *     p2p0 at boot / while a P2P group exists: 3a:08:c7:78:88:67
 *     p2p0 when idle *after* Wi-Fi Direct was used: 02:2b:d5:98:e8:da  (a randomized *interface*
 *       MAC because of `p2p_interface_random_mac_addr=1`; NOT the device address)
 *     wlan0: 3a:ab:99:10:1f:c2 (per-network randomized, NOT related to the P2P MAC)
 *
 * Because `p2p_interface_random_mac_addr` can make the idle p2p0 address differ from the P2P
 * device address, the persistent device address stored by wpa_supplicant is preferred. The order
 * is:
 *
 *  1. the persistent P2P device MAC stored in p2p_supplicant.conf
 *     (`p2p_device_persistent_mac_addr`, which is what wpa_supplicant/the framework re-use);
 *     verified to equal `WifiP2pDevice.deviceAddress` on the test device
 *  2. the framework device address (`cmd wifip2p get-device-info`, root only, only available
 *     while a P2P client is initialized)
 *  3. `/sys/class/net/p2p0/address`
 *  4. `ip -o link show p2p0`
 *  5. the WLAN MAC from the vendor/NV file (`/persist/wifi/.macaddr`, `wlan_mac.bin`) with the
 *     locally-administered bit set
 *  6. the MAC of a secondary P2P interface (p2p1 / p2p-wlan0-0)
 *  7. the factory WLAN MAC from `ro.vendor.oem.wifimac` / `ro.ril.oem.wifimac` with the
 *     locally-administered bit set
 *  8. the current wlan0 MAC with the locally-administered bit set (last resort, may be a
 *     randomized per-network MAC and therefore unreliable)
 *
 * Steps 5-8 are marked as unreliable in [MacReadResult] and logged with a warning.
 *
 * Nothing here blocks the UI thread: the blocking calls have to be invoked from a worker thread,
 * use [getMacAddress] for the asynchronous callback API or the suspend variant.
 */
object RootUtils {
    /** Interface name used by Android for the Wi-Fi Direct device address. */
    const val P2P_INTERFACE_NAME = "p2p0"

    private const val PROBE_TIMEOUT_MS = 4_000L

    /**
     * Longer timeout used for a single retry when the first probe timed out (cold start: the
     * root manager may still be initializing or showing its permission prompt).
     */
    private const val PROBE_RETRY_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 8_000L
    private const val NEGATIVE_CACHE_MS = 15_000L

    private const val ZERO_MAC = "00:00:00:00:00:00"

    /** The placeholder CatShare uses when no MAC address could be determined. */
    private const val PLACEHOLDER_MAC = "02:00:00:00:00:00"

    private val MAC_REGEX = Regex("^[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}$")
    private val LINK_ETHER_REGEX = Regex("link/ether\\s+([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})")
    private val P2P_CONF_REGEX = Regex(
        "(?:p2p_device_persistent_mac_addr|p2p_device_address|p2p_device_addr|device_addr)\\s*=\\s*" +
                "([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})"
    )
    private val FW_DEVICE_ADDRESS_REGEX = Regex(
        "deviceAddress:\\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})"
    )
    private val SECTION_REGEX = Regex("^---([A-Z0-9_]+)---$")
    private val UID_REGEX = Regex("uid=(\\d+)")
    private val INTERFACE_NAME_REGEX = Regex("^[A-Za-z0-9_.-]{1,32}$")

    private val SU_CANDIDATES = arrayOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )

    private val DETECT_COMMAND = """
        id
        if [ -d /data/adb/ksu ]; then echo ROOT_MARKER_KSU; fi
        if [ -d /data/adb/magisk ]; then echo ROOT_MARKER_MAGISK; fi
        if [ -d /data/adb/ap ]; then echo ROOT_MARKER_APATCH; fi
        command -v ksud 2>/dev/null
        command -v magisk 2>/dev/null
        exit 0
    """.trimIndent()

    data class RootStatus(
        val available: Boolean,
        /** "KernelSU", "Magisk", "APatch" or "su" when [available] is true. */
        val method: String? = null,
        /** The su binary that actually worked, e.g. "/system/bin/su". */
        val suPath: String? = null,
        val uid: Int? = null,
        val error: String? = null,
    )

    data class MacReadResult(
        val mac: String?,
        /** Human readable description of where the MAC came from. */
        val source: String,
        /** True when the MAC is the authoritative P2P device address. */
        val reliable: Boolean,
    ) {
        val found: Boolean
            get() = mac != null
    }

    private class CachedRootStatus(val status: RootStatus, val timestamp: Long)

    private class ShellResult(
        val started: Boolean,
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    )

    private val cachedStatus = AtomicReference<CachedRootStatus?>(null)
    private val detectLock = Any()

    /** Cached root status, or null when nothing was detected yet (or the cache expired). */
    fun cachedRootStatus(): RootStatus? {
        val cached = cachedStatus.get() ?: return null
        // A positive result does not change until the device is rebooted, a negative one may be
        // the result of the user not having answered the root permission prompt yet.
        if (cached.status.available) {
            return cached.status
        }
        if (System.currentTimeMillis() - cached.timestamp > NEGATIVE_CACHE_MS) {
            return null
        }
        return cached.status
    }

    /** Forget the cached detection result, e.g. after the user granted root permission. */
    fun clearCache() {
        cachedStatus.set(null)
    }

    /**
     * Detect whether a working root shell is available.
     *
     * Runs several strategies in order: the `su` binary from `PATH`, then well known KernelSU /
     * Magisk / APatch paths. Every attempt has a timeout so a pending root permission prompt can
     * never block us forever.
     *
     * This is a blocking call, do not invoke it on the main thread. Results are cached: positive
     * results forever (until reboot / [clearCache]), negative results for a short while. Pass
     * [force] to re-run the detection (used by the "re-detect" action in Settings).
     */
    fun detectRoot(force: Boolean = false): RootStatus {
        if (!force) {
            cachedRootStatus()?.let { return it }
        }

        synchronized(detectLock) {
            if (!force) {
                cachedRootStatus()?.let { return it }
            }

            var status = probeRoot()
            // On a cold start (right after boot, or the first su request of a freshly installed
            // package) the root manager can take longer than the probe timeout. Retry once
            // instead of caching "no root" and falling back to a placeholder MAC address.
            if (!status.available && status.error?.contains("timed out") == true) {
                Log.w(TAG, "Root probe timed out, retrying with a longer timeout")
                status = probeRoot(PROBE_RETRY_TIMEOUT_MS)
            }
            // Never cache a negative result that was caused by a timeout: it may succeed later.
            if (status.available || status.error?.contains("timed out") != true) {
                cachedStatus.set(CachedRootStatus(status, System.currentTimeMillis()))
            }
            if (status.available) {
                Log.i(
                    TAG,
                    "Root available via ${status.method} (${status.suPath}, uid=${status.uid})"
                )
            } else {
                Log.i(TAG, "Root unavailable: ${status.error}")
            }
            return status
        }
    }

    fun isRootAvailable(force: Boolean = false): Boolean = detectRoot(force).available

    /**
     * Read the MAC address of the Wi-Fi Direct interface (p2p0 by default) using root.
     *
     * This is a blocking call, do not invoke it on the main thread.
     *
     * @return a [MacReadResult] whose mac is null when nothing usable could be read.
     */
    fun readMacAddress(
        name: String = "p2p0",
        timeoutMs: Long = READ_TIMEOUT_MS,
    ): MacReadResult {
        if (!INTERFACE_NAME_REGEX.matches(name)) {
            Log.e(TAG, "Refusing to read MAC address for invalid interface name: $name")
            return MacReadResult(null, "invalid interface name", false)
        }

        val status = detectRoot()
        if (!status.available) {
            return MacReadResult(null, "root unavailable", false)
        }
        val su = status.suPath ?: "su"

        var result = execShell(readCommand(name), timeoutMs, su)
        if (result.timedOut) {
            Log.w(TAG, "Timed out while reading MAC address for $name, retrying once")
            result = execShell(readCommand(name), timeoutMs * 2, su)
        }
        if (result.started && !result.timedOut) {
            parseReadOutput(result.output, name)?.let { macResult ->
                logResult(name, macResult)
                return macResult
            }
        } else if (result.timedOut) {
            Log.w(TAG, "Timed out while reading MAC address for $name via root")
        }

        Log.w(TAG, "Failed to read a usable MAC address for $name with root")
        return MacReadResult(null, "none", false)
    }

    /** Asynchronous wrapper of [readMacAddress] that calls [callback] from a worker thread. */
    fun getMacAddress(name: String = "p2p0", callback: (String?) -> Unit) {
        val thread = Thread {
            val mac = try {
                readMacAddress(name).mac
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to obtain MAC address for $name via root", e)
                null
            }
            callback(mac)
        }
        thread.isDaemon = true
        thread.start()
    }

    /**
     * Read the Wi-Fi Direct MAC address (`p2p0`) using root.
     *
     * This is a blocking call, do not invoke it on the main thread. Returns null when no usable
     * address could be read (e.g. no root, or the interface is not available and no fallback
     * worked).
     *
     * This is the entry point shared with the PC sharing service, use [readP2pMacAddress] when
     * you also need to know where the value came from.
     */
    fun getP2pMacAddress(): String? = readMacAddress(P2P_INTERFACE_NAME).mac

    /**
     * Blocking variant of [getP2pMacAddress] that also reports the source and whether the value is
     * the authoritative P2P device address.
     */
    fun readP2pMacAddress(): MacReadResult = readMacAddress(P2P_INTERFACE_NAME)

    /** Asynchronous variant of [getP2pMacAddress] calling [callback] from a worker thread. */
    fun getP2pMacAddress(callback: (String?) -> Unit) = getMacAddress(P2P_INTERFACE_NAME, callback)

    /** Suspend variant of [getMacAddress], matching the style used by [ShizukuUtils]. */
    suspend fun getMacAddress(name: String = "p2p0"): String? {
        val future = CompletableDeferred<String?>()
        getMacAddress(name) {
            future.complete(it)
        }
        return future.await()
    }

    /** Normalize a MAC address to the lowercase `aa:bb:cc:dd:ee:ff` format used by CatShare. */
    fun normalizeMac(raw: String?): String? {
        if (raw == null) {
            return null
        }
        val candidate = raw.trim().replace('-', ':').lowercase()
        if (!MAC_REGEX.matches(candidate)) {
            return null
        }
        if (candidate == ZERO_MAC || candidate == PLACEHOLDER_MAC) {
            return null
        }
        val firstOctet = candidate.substring(0, 2).toInt(16)
        // Multicast/broadcast addresses are never a valid interface address.
        if (firstOctet and 0x01 != 0) {
            return null
        }
        return candidate
    }

    fun isValidMac(raw: String?): Boolean = normalizeMac(raw) != null

    private fun probeRoot(timeoutMs: Long = PROBE_TIMEOUT_MS): RootStatus {
        val candidates = buildList {
            for (candidate in SU_CANDIDATES) {
                if (candidate == "su" || File(candidate).exists()) {
                    add(candidate)
                }
            }
        }

        var lastError: String? = null
        for (candidate in candidates) {
            val result = execShell(DETECT_COMMAND, timeoutMs, candidate)
            if (!result.started) {
                lastError = "cannot execute $candidate"
                continue
            }
            if (result.timedOut) {
                lastError = "timed out waiting for root permission"
                break
            }
            if (result.exitCode != 0) {
                lastError = firstNonEmptyLine(result.output)
                    ?: "su exited with code ${result.exitCode}"
                // su exists but denied the request: trying other binaries would only spam more
                // permission prompts.
                break
            }

            val uid = UID_REGEX.find(result.output)?.groupValues?.get(1)?.toIntOrNull()
            if (uid == 0) {
                return RootStatus(true, detectMethod(result.output), candidate, uid, null)
            }
            lastError = "su did not return a root shell"
            break
        }

        return RootStatus(false, null, null, null, lastError ?: "su not found")
    }

    private fun detectMethod(output: String): String {
        val hasKsud = output.lineSequence().any { it.trim().endsWith("/ksud") }
        val hasMagisk = output.lineSequence().any { it.trim().endsWith("/magisk") }
        return when {
            output.contains("ROOT_MARKER_KSU") || hasKsud -> "KernelSU"
            output.contains("ROOT_MARKER_MAGISK") || hasMagisk -> "Magisk"
            output.contains("ROOT_MARKER_APATCH") -> "APatch"
            else -> "su"
        }
    }

    /**
     * One root shell invocation reading every candidate source. A single `su` call avoids several
     * expensive/visible root requests per resolution.
     */
    private fun readCommand(name: String): String = """
        echo "---CONF---"
        grep -hE 'p2p_device_persistent_mac_addr|p2p_device_address|p2p_device_addr' /data/vendor/wifi/wpa/p2p_supplicant.conf /data/misc/wifi/p2p_supplicant.conf /data/misc/wifi/wpa_supplicant.conf /data/vendor/wifi/wpa/wpa_supplicant.conf 2>/dev/null
        echo "---FW---"
        cmd wifip2p get-device-info 2>/dev/null
        echo "---SYSMAIN---"
        cat /sys/class/net/$name/address 2>/dev/null
        echo "---LINKMAIN---"
        ip -o link show $name 2>/dev/null
        echo "---SYSWLAN---"
        cat /sys/class/net/wlan0/address 2>/dev/null
        echo "---NV---"
        cat /persist/wifi/.macaddr 2>/dev/null
        if [ -f /data/vendor/wifi/wlan_mac.bin ]; then
            cat /data/vendor/wifi/wlan_mac.bin 2>/dev/null
            [ "$(wc -c < /data/vendor/wifi/wlan_mac.bin)" = "6" ] && od -An -tx1 /data/vendor/wifi/wlan_mac.bin 2>/dev/null
        fi
        echo "---P2PIF---"
        cat /sys/class/net/p2p1/address 2>/dev/null
        cat /sys/class/net/p2p-wlan0-0/address 2>/dev/null
        echo "---PROPS---"
        getprop ro.vendor.oem.wifimac
        getprop ro.ril.oem.wifimac
        getprop ro.vendor.wifi.mac
        getprop persist.vendor.wifi.mac
        getprop ro.wifi.mac
        echo "---LINKWLAN---"
        ip -o link show wlan0 2>/dev/null
        echo "---END---"
    """.trimIndent()

    private fun parseReadOutput(output: String, name: String): MacReadResult? {
        val sections = splitSections(output)

        // 1. Persistent P2P device MAC stored by wpa_supplicant; this is the address advertised as
        //    WifiP2pDevice.deviceAddress and it survives P2P interface MAC randomization.
        sections["CONF"]?.let { conf ->
            P2P_CONF_REGEX.findAll(conf).forEach { match ->
                normalizeMac(match.groupValues[1])?.let {
                    return MacReadResult(it, "p2p_supplicant.conf persistent device address", true)
                }
            }
        }

        // 2. Framework P2P device address (only available while a P2P client is initialized).
        sections["FW"]?.let { fw ->
            FW_DEVICE_ADDRESS_REGEX.find(fw)?.groupValues?.get(1)?.let { raw ->
                normalizeMac(raw)?.let {
                    return MacReadResult(it, "wifip2p device address", true)
                }
            }
        }

        // 3. The p2p0 interface itself (may be a randomized interface MAC on some devices).
        normalizeMac(sections["SYSMAIN"]?.trim())?.let {
            return MacReadResult(it, "sysfs:/sys/class/net/$name/address", true)
        }

        // 4. `ip -o link show p2p0`.
        sections["LINKMAIN"]?.let { linkSection ->
            extractLinkEther(linkSection)?.let {
                return MacReadResult(it, "ip-link:$name", true)
            }
        }

        // 5. Factory WLAN MAC from the vendor/NV file, with the locally-administered bit set.
        sections["NV"]?.let { nv ->
            val nvMac = extractAnyMac(nv) ?: parseNvBinarySection(nv)
            nvMac?.let {
                withLocallyAdministeredBit(it)?.let { derived ->
                    return MacReadResult(derived, "vendor NV wlan MAC (derived)", false)
                }
            }
        }

        // 6. A secondary P2P interface (group interface MAC, may be randomized per session).
        sections["P2PIF"]?.let { p2pIf ->
            extractAnyMac(p2pIf)?.let {
                return MacReadResult(it, "secondary p2p interface", false)
            }
        }

        // 7. Factory WLAN MAC property, with the locally-administered bit set.
        sections["PROPS"]?.let { props ->
            props.lineSequence().mapNotNull { normalizeMac(it) }.firstOrNull()?.let { factoryWlan ->
                withLocallyAdministeredBit(factoryWlan)?.let {
                    return MacReadResult(it, "vendor wifimac property (derived)", false)
                }
            }
        }

        // 8. Last resort: the current (possibly randomized) wlan0 MAC.
        sections["SYSWLAN"]?.let { sysWlan ->
            normalizeMac(sysWlan.lineSequence().firstOrNull())?.let { wlanMac ->
                withLocallyAdministeredBit(wlanMac)?.let { derived ->
                    return MacReadResult(derived, "wlan0 (derived, unreliable)", false)
                }
            }
        }
        sections["LINKWLAN"]?.let { linkWlan ->
            extractLinkEther(linkWlan)?.let { wlanMac ->
                withLocallyAdministeredBit(wlanMac)?.let { derived ->
                    return MacReadResult(derived, "wlan0 (derived, unreliable)", false)
                }
            }
        }

        return null
    }

    private fun parseNvBinarySection(section: String): String? {
        // `od -An -tx1` output: the first six bytes of files such as wlan_mac.bin.
        for (line in section.lineSequence()) {
            val bytes = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (bytes.size < 6) {
                continue
            }
            val hex = bytes.take(6)
            if (hex.any { it.length != 2 || it.toIntOrNull(16) == null }) {
                continue
            }
            val mac = hex.joinToString(":") { it.lowercase() }
            normalizeMac(mac)?.let { return it }
        }
        return null
    }

    private fun splitSections(output: String): Map<String, String> {
        val sections = mutableMapOf<String, StringBuilder>()
        var current: StringBuilder? = null
        for (line in output.lineSequence()) {
            val sectionName = SECTION_REGEX.matchEntire(line.trim())?.groupValues?.get(1)
            if (sectionName != null) {
                current = StringBuilder()
                sections[sectionName] = current
            } else {
                current?.appendLine(line)
            }
        }
        return sections.mapValues { it.value.toString() }
    }

    private fun extractLinkEther(text: String): String? {
        return LINK_ETHER_REGEX.find(text)?.groupValues?.get(1)?.let { normalizeMac(it) }
    }

    private fun extractAnyMac(text: String): String? {
        return MAC_REGEX.findAll(text)
            .mapNotNull { normalizeMac(it.value) }
            .firstOrNull()
    }

    /** Android derives the P2P MAC from the WLAN MAC by setting the locally-administered bit. */
    private fun withLocallyAdministeredBit(mac: String): String? {
        val normalized = normalizeMac(mac) ?: return null
        val octets = normalized.split(":")
        val first = octets[0].toInt(16) or 0x02
        val derived = (listOf(String.format("%02x", first)) + octets.drop(1)).joinToString(":")
        return normalizeMac(derived)
    }

    private fun logResult(name: String, result: MacReadResult) {
        if (result.reliable) {
            Log.i(TAG, "Read ${name} MAC address ${result.mac} via ${result.source}")
        } else {
            Log.w(
                TAG,
                "Could not read ${name} address directly, using derived MAC ${result.mac} " +
                        "from ${result.source} (may be inaccurate)"
            )
        }
    }

    private fun firstNonEmptyLine(text: String): String? {
        return text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120)
    }

    private fun execShell(command: String, timeoutMs: Long, su: String): ShellResult {
        val process = try {
            ProcessBuilder(su, "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: Throwable) {
            Log.d(TAG, "Failed to execute $su: ${e.message}")
            return ShellResult(false, -1, "", false)
        }

        val output = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = reader.read(buffer)
                        if (count < 0) {
                            break
                        }
                        synchronized(output) {
                            output.append(buffer, 0, count)
                        }
                    }
                }
            } catch (_: Throwable) {
                // The process was killed or closed, whatever was read is enough.
            }
        }
        readerThread.isDaemon = true
        readerThread.start()

        val finished = try {
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!finished) {
            try {
                process.destroyForcibly()
            } catch (_: Throwable) {
                // ignored
            }
            readerThread.interrupt()
            val partialOutput = synchronized(output) { output.toString() }
            Log.w(TAG, "Root command timed out after ${timeoutMs}ms")
            return ShellResult(true, -1, partialOutput, true)
        }

        try {
            readerThread.join(1_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        val text = synchronized(output) { output.toString() }
        return ShellResult(true, process.exitValue(), text, false)
    }
}
