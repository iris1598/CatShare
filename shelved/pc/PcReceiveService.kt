package moe.reimu.catshare.pc

import android.app.Notification
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.utils.NotificationUtils
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

/**
 * Receives files from a Xiaomi PC (legacy MiDrop "PC mode"): the PC advertises a
 * transfer packet over BLE, creates a Wi-Fi hotspot and serves files over
 * HTTPS API 1.0; the phone joins the hotspot and downloads.
 */
class PcReceiveService : Service() {
    private val TAG = "CatSharePc"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var advThread: Thread? = null
    private var scanner: PcBle.Scanner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NotificationUtils.RECEIVER_FG_ID,
            buildNotification("等待小米电脑", "正在等待电脑发起互传…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        scope.launch {
            try {
                runReceive(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "receive failed", e)
                updateNotification("接收失败", e.message ?: e.toString())
            } finally {
                cleanUp()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runReceive(intent: Intent) {
        val settings = AppSettings(this)
        val selfDeviceId = Random.nextInt(0x10000) and 0xffff
        var peerDeviceId = intent.getIntExtra("peer_device_id", 0)
        var taskId = intent.getIntExtra("task_id", 0)
        var base = intent.getStringExtra("server_base")
        val debugMode = base != null

        if (!debugMode) {
            if (!PcRootShell.isRootAvailable()) {
                updateNotification("无法接收", "PC 互传需要 Root 权限（用于连接电脑热点）")
                return
            }
            val nameBytes = PcProtocol.pskNameBytes(settings.deviceName.ifBlank { "Android" })
            val ability = PcProtocol.abilityPacket(
                settings.deviceName.ifBlank { "Android" },
                selfDeviceId,
                PcProtocol.AbilityFlags(a = 1, b = 0, c = 0, d = 1, e = 1, f = 0, g = 0, h = 1, i = 0, j = 0),
            )
            advThread = PcBle.advertiseSequence(this, "recv-ability", listOf(ability), durationMs = 1500)

            val transferFuture = CompletableDeferred<PcProtocol.TransferAdvert>()
            scanner = PcBle.Scanner(this) { _addr, _rssi, data ->
                val t = PcProtocol.parseTransfer(data)
                if (t != null && !transferFuture.isCompleted) {
                    Log.i(TAG, "PC transfer advert: task=${t.taskId} peer=${t.b} nonce=${t.nonce} fileCount=${t.fileCount}")
                    transferFuture.complete(t)
                }
            }
            scanner?.start()
            val transfer = withTimeoutOrNull(90_000) { transferFuture.await() }
            if (transfer == null) {
                updateNotification("未发现小米电脑", "请先在电脑的“小米互传”中向本机发送文件")
                return
            }
            scanner?.stop()
            scanner = null

            peerDeviceId = transfer.b
            taskId = transfer.taskId
            val pcNameBytes = transfer.name?.toByteArray(Charsets.UTF_8)?.copyOfRange(
                0, minOf(6, transfer.name?.toByteArray(Charsets.UTF_8)?.size ?: 0)
            ) ?: nameBytes
            val ssid = PcProtocol.hotspotSsid(peerDeviceId, transfer.support2_0)
            val psk = PcProtocol.hotspotPsk(peerDeviceId, transfer.nonce, pcNameBytes)
            if (psk == null) {
                updateNotification("接收失败", "无法计算电脑热点密码")
                return
            }
            updateNotification("正在连接电脑热点", ssid)
            val qSsid = "'" + ssid.replace("'", "'\\''") + "'"
            val qPsk = "'" + psk.replace("'", "'\\''") + "'"
            val join = PcRootShell.run("cmd wifi connect-network $qSsid wpa2 $qPsk", 30000)
            Log.i(TAG, "join PC hotspot code=${join.code} out=${join.output.take(200)}")
            // Tell the PC that we accepted the mission.
            val response = PcProtocol.responsePacket(selfDeviceId, peerDeviceId, taskId, true)
            PcBle.advertise(this, response, 5000)
            base = if (transfer.ipLastTwo.size == 2 && transfer.ipLastTwo.any { it != 0 }) {
                "https://192.168.${transfer.ipLastTwo[0]}.${transfer.ipLastTwo[1]}:9999"
            } else {
                "https://192.168.137.1:9999"
            }
        }

        updateNotification("正在接收", "从电脑下载文件…")
        val user = selfDeviceId.toString()
        val task = taskId.toString()
        val fileInfoRaw = httpGet("$base/api/1.0/fileinfo?user=$user&task=$task")
        Log.i(TAG, "fileinfo: $fileInfoRaw")
        val root = JSONObject(fileInfoRaw)
        val data = root.optJSONArray("data") ?: return
        var received = 0
        var totalSize = 0L
        for (i in 0 until data.length()) {
            val f = data.getJSONObject(i)
            val id = f.optString("id")
            val name = f.optString("name")
            val size = f.optLong("size", 0)
            val expectedMd5 = f.optString("md5")
            totalSize += size
            updateNotification("正在接收", "$name (${i + 1}/${data.length()})")
            val bytes = httpGetBytes("$base/api/1.0/file?user=$user&task=$task&id=$id")
            if (size > 0 && bytes.size.toLong() != size) {
                Log.w(TAG, "size mismatch for $name: ${bytes.size} != $size")
            }
            val md5 = md5(bytes)
            if (expectedMd5.isNotEmpty() && !expectedMd5.equals(md5, true)) {
                Log.w(TAG, "md5 mismatch for $name")
            }
            saveToDownloads(name, bytes)
            received++
            val verifyBody = JSONObject()
                .put("status", 0)
                .put(
                    "data", JSONObject()
                        .put("md5", md5)
                        .put("srcname", name)
                        .put("srcpath", "")
                        .put("dstname", name)
                        .put("dstpath", "")
                        .put("reason", "")
                        .put("id", id)
                        .put("code", 0)
                        .put("cur", size)
                        .put("total", size)
                ).toString()
            Log.i(TAG, "fileverify: " + httpPost("$base/api/1.0/fileverify?user=$user&task=$task", verifyBody))
            Log.i(TAG, "report: " + httpPost("$base/api/1.0/report?user=$user&task=$task", verifyBody))
        }
        Log.i(TAG, "taskcomplete: " + httpPost("$base/api/1.0/taskcomplete?user=$user&task=$task", "{}"))
        updateNotification("接收完成", "已接收 $received 个文件（${totalSize} 字节）")
    }

    private fun trustAllContext(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return ctx
    }

    private fun openConnection(url: String): HttpsURLConnection {
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.sslSocketFactory = trustAllContext().socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        return conn
    }

    private fun httpGet(url: String): String {
        val conn = openConnection(url)
        conn.requestMethod = "GET"
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = openConnection(url)
        conn.requestMethod = "GET"
        return conn.inputStream.use { it.readBytes() }
    }

    private fun httpPost(url: String, body: String): String {
        val conn = openConnection(url)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun md5(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun saveToDownloads(name: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/MiShare"
            )
        }
        try {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                Log.i(TAG, "saved $name to Downloads/MiShare")
            } else {
                fallbackSave(name, bytes)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "save failed, fallback", e)
            fallbackSave(name, bytes)
        }
    }

    private fun fallbackSave(name: String, bytes: ByteArray) {
        val dir = File(getExternalFilesDir(null), "MiShare")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
        Log.i(TAG, "saved $name to ${dir.absolutePath}")
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, NotificationUtils.RECEIVER_CHAN_ID)
            .setSmallIcon(moe.reimu.catshare.R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(title: String, text: String) {
        try {
            NotificationManagerCompat.from(this)
                .notify(NotificationUtils.RECEIVER_FG_ID, buildNotification(title, text))
        } catch (e: Throwable) {
            Log.e(TAG, "notify failed", e)
        }
    }

    private fun cleanUp() {
        advThread?.interrupt()
        advThread = null
        scanner?.stop()
        scanner = null
    }

    companion object {
        fun start(
            context: Context,
            serverBase: String? = null,
            peerDeviceId: Int = 0,
            taskId: Int = 0,
        ) {
            val intent = Intent(context, PcReceiveService::class.java).apply {
                serverBase?.let { putExtra("server_base", it) }
                putExtra("peer_device_id", peerDeviceId)
                putExtra("task_id", taskId)
            }
            context.startForegroundService(intent)
        }
    }
}
