package moe.reimu.catshare.pc

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Controls the phone's Wi-Fi hotspot (soft AP) through root.
 *
 * Android does not allow normal apps to create a soft AP; the original Xiaomi
 * app is a system app. Since the user's device is rooted we can use the hidden
 * `cmd wifi start-softap` shell command instead.
 */
object PcHotspot {
    private const val TAG = "CatSharePc"

    private var running = false
    var ssid: String? = null
        private set
    var psk: String? = null
        private set

    /** Starts the soft AP on the 2.4GHz band and waits for ap0 to get an IPv4 address. */
    fun start(ssid: String, psk: String, band: String = "2"): String? {
        stop()
        Log.i(TAG, "starting hotspot ssid=$ssid band=$band")
        val qSsid = shellQuote(ssid)
        val qPsk = shellQuote(psk)
        val result = PcRootShell.run(
            "cmd wifi start-softap $qSsid wpa2 $qPsk $band", 25000
        )
        Log.i(TAG, "start-softap result code=${result.code} out=${result.output.take(400)}")
        running = true
        this.ssid = ssid
        this.psk = psk

        var ip: String? = null
        repeat(30) {
            ip = apIpAddress()
            if (ip != null) return ip
            Thread.sleep(500)
        }
        Log.w(TAG, "hotspot did not get an IP (ap0)")
        return null
    }

    /** Returns the IPv4 address of the AP interface (e.g. 192.168.43.1 or 192.168.156.196). */
    fun apIpAddress(): String? {
        val r = PcRootShell.run("ip -4 -o addr show ap0 2>/dev/null", 6000)
        val match = Regex("inet (\\d+\\.\\d+\\.\\d+\\.\\d+)").find(r.output) ?: return null
        return match.groupValues[1]
    }

    /** Last two octets of the AP IP, as announced in server-ready packets. */
    fun apIpLastTwo(): IntArray? {
        val ip = apIpAddress() ?: return null
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return intArrayOf(parts[2].toIntOrNull() ?: 0, parts[3].toIntOrNull() ?: 0)
    }

    fun stop() {
        if (running) {
            Log.i(TAG, "stopping hotspot")
            PcRootShell.run("cmd wifi stop-softap", 12000)
        }
        running = false
        ssid = null
        psk = null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
