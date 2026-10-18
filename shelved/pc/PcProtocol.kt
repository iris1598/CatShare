package moe.reimu.catshare.pc

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Xiaomi legacy MiDrop "PC mode" wire format.
 *
 * PC mode is used when a Xiaomi phone talks to the Xiaomi PC app (HyperConnect /
 * 小米互传). Unlike the 互传联盟 (MTA) protocol used phone<->phone, it does not use
 * a BLE GATT link: both sides only exchange connectable=false BLE advertisements
 * whose manufacturer data (company id 0x038F, Xiaomi Inc.) carries small packets,
 * while the actual file transfer happens over HTTPS API 1.0 (port 9999) on a
 * Wi-Fi hotspot created by one of the peers.
 *
 * Layouts below were recovered from com.miui.mishare.connectivity 5.4.2
 * (o1/d = PCBlePacketHelper, n1/b = SSID/PSK helpers, r1/a = AES helper).
 */
object PcProtocol {
    const val MANUFACTURER_ID = 0x038F
    const val HTTP_PORT = 9999

    // Windows mobile hotspot gateway / Android hotspot gateway used by the original app
    const val WINDOWS_HOTSPOT_GATEWAY = "192.168.137.1"
    const val ANDROID_HOTSPOT_GATEWAY = "192.168.43.1"

    const val AES_KEY_HEX = "15c8761d9e36456b9af35e1d084665a5"
    const val AES_IV_HEX = "02050b11171f292f3b43495361676d7f"

    private const val DEFAULT_KEY_ALIAS = "pc"
    private const val DEFAULT_KEYSTORE_PASSWORD = "ih9gqpx"

    /** p1/b: ability flags advertised by a device. Only == 1 counts as "set". */
    data class AbilityFlags(
        val a: Int = 0,
        val b: Int = 0,
        val c: Int = 0,
        val d: Int = 0,
        val e: Int = 0,
        val f: Int = 0,
        val g: Int = 0,
        val h: Int = 0,
        val i: Int = 0,
        val j: Int = 0,
    ) {
        fun packFirst(): Byte {
            var v = 0
            if (a == 1) v = v or 0x08
            if (b == 1) v = v or 0x10
            if (c == 1) v = v or 0x20
            if (d == 1) v = v or 0x40
            if (e == 1) v = v or 0x80
            return v.toByte()
        }

        fun packSecond(): Byte {
            var v = f and 0xff
            if (g == 1) v = v or 0x08
            if (h == 1) v = v or 0x10
            if (i == 1) v = v or 0x20
            v = v or ((j and 0xff) shl 6)
            return v.toByte()
        }
    }

    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }
        return out
    }

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Hotspot SSID announced for a peer, derived from the 16 bit device id.
     * support2_0 == false -> "ap_mishare_XXXX" (soft AP), true -> "DIRECT-XXXX" (Wi-Fi Direct GO).
     */
    fun hotspotSsid(deviceId: Int, support2_0: Boolean): String =
        if (support2_0) "DIRECT-%04x".format(deviceId and 0xffff)
        else "ap_mishare_%04x".format(deviceId and 0xffff)

    /**
     * Hotspot passphrase: first 8 chars of Base64(DEFAULT) of
     * AES/CBC/PKCS5Padding(data, key, iv) where data is 8 bytes:
     * [deviceId lo, deviceId hi, channel lo, channel hi, name[0..3] (zero padded)].
     */
    fun hotspotPsk(deviceId: Int, p3: Int, name: ByteArray): String? {
        val buf = ByteArray(8)
        buf[0] = (deviceId and 0xff).toByte()
        buf[1] = ((deviceId ushr 8) and 0xff).toByte()
        buf[2] = (p3 and 0xff).toByte()
        buf[3] = ((p3 ushr 8) and 0xff).toByte()
        for (i in 4..7) {
            buf[i] = if (i - 4 < name.size) name[i - 4] else 0
        }
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(hexToBytes(AES_KEY_HEX), "AES"),
                IvParameterSpec(hexToBytes(AES_IV_HEX))
            )
            val encrypted = cipher.doFinal(buf)
            val b64 = Base64.encodeToString(encrypted, Base64.DEFAULT)
            if (b64.length >= 8) b64.substring(0, 8) else null
        } catch (e: Throwable) {
            null
        }
    }

    /** Device name bytes used for the PSK derivation: the first 6 bytes of the UTF-8 name. */
    fun pskNameBytes(name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        return raw.copyOfRange(0, minOf(6, raw.size))
    }

    /**
     * Ability packet (advertised continuously by a discoverable device).
     * Mirrors o1/d.a(String name, int deviceId, p1/b flags).
     */
    fun abilityPacket(name: String, deviceId: Int, flags: AbilityFlags): ByteArray {
        var nameBytes = name.toByteArray(Charsets.UTF_8)
        // The legacy advertisement only has room for 12 bytes of name.
        if (nameBytes.size > 12) nameBytes = nameBytes.copyOf(12)
        val n = nameBytes.size
        val out = ByteArray(n + 13)
        out[0] = ((n + 12) and 0xff).toByte()
        out[1] = 0x11
        out[2] = 0x00
        out[3] = 0x11
        out[4] = 0x12 // f(false)
        out[5] = (((n and 0x0f) shl 4) or 0x03).toByte()
        System.arraycopy(nameBytes, 0, out, 6, n)
        out[n + 6] = 0x24
        out[n + 7] = (deviceId and 0xff).toByte()
        out[n + 8] = ((deviceId ushr 8) and 0xff).toByte()
        out[n + 9] = 0x38
        out[n + 10] = flags.packFirst()
        out[n + 11] = flags.packSecond()
        // Random low byte - 30 so that peer can recover a random nibble; value itself is not validated.
        val rand = (kotlin.random.Random.nextInt(256) - 30) and 0xff
        out[n + 12] = rand.toByte()
        return out
    }

    /**
     * "Server ready" packet (phone as sender announces its HTTPS server).
     * Mirrors o1/d.d(int a, int b, boolean flag, WifiApTaskInfo info).
     */
    fun serverReadyPacket(
        selfDeviceId: Int,
        peerDeviceId: Int,
        flag: Boolean,
        taskId: Int,
        ipLastTwo: IntArray,
    ): ByteArray {
        val out = ByteArray(16)
        out[0] = 0x0f
        out[1] = 0x11
        out[2] = 0x00
        out[3] = 0x11
        out[4] = if (flag) 0x02 else 0x12
        out[5] = 0x24
        out[6] = (selfDeviceId and 0xff).toByte()
        out[7] = ((selfDeviceId ushr 8) and 0xff).toByte()
        out[8] = 0x78
        out[9] = 0x04
        out[10] = (peerDeviceId and 0xff).toByte()
        out[11] = ((peerDeviceId ushr 8) and 0xff).toByte()
        out[12] = (taskId and 0xff).toByte()
        out[13] = ((taskId ushr 8) and 0xff).toByte()
        out[14] = (ipLastTwo.getOrElse(0) { 0 } and 0xff).toByte()
        out[15] = (ipLastTwo.getOrElse(1) { 0 } and 0xff).toByte()
        return out
    }

    /**
     * Transfer control packet (peer as sender announces hotspot + task details).
     * Mirrors o1/d.e(int a, int b, WifiApTaskInfo info, byte[] nameBytes).
     */
    fun transferControlPacket(
        selfDeviceId: Int,
        peerDeviceId: Int,
        support2_0: Boolean,
        fileCount: Int,
        channel: Int,
        taskId: Int,
        nonce: Int,
        nameBytes: ByteArray,
        ipLastTwo: IntArray,
        sdkInt: Int,
    ): ByteArray {
        val n = nameBytes.size
        val sdkExtra = if (sdkInt > 29) 3 else 0
        val out = ByteArray(n + 0x12 + sdkExtra)
        out[0] = ((n + 0x11) and 0xff).toByte()
        out[1] = 0x11
        out[2] = 0x00
        out[3] = 0x11
        out[4] = 0x02 // f(true)
        out[5] = 0x24
        out[6] = (selfDeviceId and 0xff).toByte()
        out[7] = ((selfDeviceId ushr 8) and 0xff).toByte()
        out[8] = ((((n + 9) and 0x0f) shl 4) or 0x08).toByte()
        out[9] = ((if (support2_0) 0x10 else 0x00) or 0x09).toByte()
        val capped = if (fileCount > 250) 255 else fileCount
        out[10] = (capped and 0xff).toByte()
        out[11] = (channel and 0xff).toByte()
        out[12] = (peerDeviceId and 0xff).toByte()
        out[13] = ((peerDeviceId ushr 8) and 0xff).toByte()
        out[14] = (taskId and 0xff).toByte()
        out[15] = ((taskId ushr 8) and 0xff).toByte()
        out[16] = (nonce and 0xff).toByte()
        out[17] = ((nonce ushr 8) and 0xff).toByte()
        System.arraycopy(nameBytes, 0, out, 18, n)
        if (sdkExtra == 3) {
            out[n + 18] = 0x2f
            out[n + 19] = (ipLastTwo.getOrElse(0) { 0 } and 0xff).toByte()
            out[n + 20] = (ipLastTwo.getOrElse(1) { 0 } and 0xff).toByte()
        }
        return out
    }

    /** Cancel packet. Mirrors o1/d.b(int a, int b, int taskId). */
    fun cancelPacket(a: Int, b: Int, taskId: Int): ByteArray {
        val out = ByteArray(14)
        out[0] = 0x0d
        out[1] = 0x11
        out[2] = 0x00
        out[3] = 0x11
        out[4] = 0x12
        out[5] = 0x24
        out[6] = (a and 0xff).toByte()
        out[7] = ((a ushr 8) and 0xff).toByte()
        out[8] = 0x58
        out[9] = 0x0a
        out[10] = (b and 0xff).toByte()
        out[11] = ((b ushr 8) and 0xff).toByte()
        out[12] = (taskId and 0xff).toByte()
        out[13] = ((taskId ushr 8) and 0xff).toByte()
        return out
    }

    /** Response packet. Mirrors o1/d.c(int a, int b, int c, boolean success).
     *  The phone treats subtype 0x10 as default ack; we send 0x13 for ok / 0x23 for failure. */
    fun responsePacket(a: Int, b: Int, c: Int, success: Boolean): ByteArray {
        val out = ByteArray(14)
        out[0] = 0x0d
        out[1] = 0x11
        out[2] = 0x00
        out[3] = 0x11
        out[4] = 0x12
        out[5] = 0x24
        out[6] = (a and 0xff).toByte()
        out[7] = ((a ushr 8) and 0xff).toByte()
        out[8] = 0x58
        out[9] = if (success) 0x13 else 0x23
        out[10] = (b and 0xff).toByte()
        out[11] = ((b ushr 8) and 0xff).toByte()
        out[12] = (c and 0xff).toByte()
        out[13] = ((c ushr 8) and 0xff).toByte()
        return out
    }

    // ---------------------------------------------------------------------
    // Parsing of advertisements received from the PC
    // ---------------------------------------------------------------------

    data class AbilityAdvert(
        val name: String?,
        val deviceId: Int?,
        val raw: ByteArray,
    )

    /** Parses an ability packet (same layout as the one we send). */
    fun parseAbility(data: ByteArray): AbilityAdvert? {
        if (data.size < 12) return null
        var name: String? = null
        var deviceId: Int? = null
        // name TLV: byte 5 has (len<<4)|3
        val nameLen = (data[5].toInt() ushr 4) and 0x0f
        val nameStart = 6
        if (nameLen in 1..12 && nameStart + nameLen <= data.size) {
            name = String(data, nameStart, nameLen, Charsets.UTF_8)
        }
        val devIndex = nameStart + nameLen + 1
        if (devIndex + 1 < data.size && data[devIndex].toInt() == 0x24) {
            val lo = data[devIndex + 1].toInt() and 0xff
            val hi = data[devIndex + 2].toInt() and 0xff
            deviceId = (hi shl 8) or lo
        }
        return AbilityAdvert(name, deviceId, data)
    }

    data class ServerReadyAdvert(
        val a: Int,
        val b: Int,
        val flag: Boolean,
        val taskId: Int,
        val ipLastTwo: IntArray,
        val raw: ByteArray,
    )

    fun parseServerReady(data: ByteArray): ServerReadyAdvert? {
        if (data.size < 16) return null
        if (data[0].toInt() and 0xff != 0x0f) return null
        val a = (data[6].toInt() and 0xff) or ((data[7].toInt() and 0xff) shl 8)
        val b = (data[10].toInt() and 0xff) or ((data[11].toInt() and 0xff) shl 8)
        val task = (data[12].toInt() and 0xff) or ((data[13].toInt() and 0xff) shl 8)
        return ServerReadyAdvert(
            a, b, data[4].toInt() == 0x02, task,
            intArrayOf(data[14].toInt() and 0xff, data[15].toInt() and 0xff), data
        )
    }

    data class TransferAdvert(
        val a: Int,
        val b: Int,
        val support2_0: Boolean,
        val fileCount: Int,
        val channel: Int,
        val taskId: Int,
        val nonce: Int,
        val name: String?,
        val ipLastTwo: IntArray,
        val raw: ByteArray,
    )

    fun parseTransfer(data: ByteArray): TransferAdvert? {
        if (data.size < 18) return null
        val n = ((data[8].toInt() ushr 4) and 0x0f) - 9
        val nameBytes = if (n in 1..8 && 18 + n <= data.size) data.copyOfRange(18, 18 + n) else ByteArray(0)
        val ip = if (data.size >= 21) intArrayOf(data[data.size - 2].toInt() and 0xff, data[data.size - 1].toInt() and 0xff) else intArrayOf(0, 0)
        return TransferAdvert(
            a = (data[12].toInt() and 0xff) or ((data[13].toInt() and 0xff) shl 8),
            b = (data[6].toInt() and 0xff) or ((data[7].toInt() and 0xff) shl 8),
            support2_0 = (data[9].toInt() and 0x10) != 0,
            fileCount = data[10].toInt() and 0xff,
            channel = data[11].toInt() and 0xff,
            taskId = (data[14].toInt() and 0xff) or ((data[15].toInt() and 0xff) shl 8),
            nonce = (data[16].toInt() and 0xff) or ((data[17].toInt() and 0xff) shl 8),
            name = if (nameBytes.isNotEmpty()) String(nameBytes, Charsets.UTF_8) else null,
            ipLastTwo = ip,
            raw = data,
        )
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
