package moe.reimu.catshare.pc

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.reimu.catshare.AppSettings
import moe.reimu.catshare.models.FileInfo
import moe.reimu.catshare.models.TaskInfo
import moe.reimu.catshare.utils.NotificationUtils
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** One file shared with the PC, with lazily computed MD5. */
data class PcSharedFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val file: File,
    val mimeTypeCode: Int,
) {
    @Volatile
    var md5: String? = null
}

/** Mutable state of a "phone sends to PC" session, shared with the HTTP server. */
class PcSendSession(
    val taskId: Int,
    val peerDeviceId: Int,
    val files: List<PcSharedFile>,
    private val onProgress: (Long) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val TAG = "CatSharePc"
    val processedBytes = AtomicLong(0)
    val servedFiles = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    val completed = AtomicBoolean(false)
    val canceled = AtomicBoolean(false)

    fun findFile(id: String?): PcSharedFile? = files.firstOrNull { it.id == id }

    fun onBytes(read: Int) {
        onProgress(processedBytes.addAndGet(read.toLong()))
    }

    fun onFileServed(file: PcSharedFile) {
        servedFiles.add(file.id)
    }

    /** Handles /api/1.0/fileverify: body JSON with data.id / data.md5. */
    fun verifyFile(body: String): Pair<Boolean, String> {
        return try {
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val remoteMd5 = data?.optString("md5", "") ?: ""
            val id = data?.optString("id", "") ?: ""
            val file = files.firstOrNull { it.id == id } ?: files.firstOrNull()
            val localMd5 = file?.md5
            if (localMd5 == null || remoteMd5.isEmpty()) {
                // Do not block the transfer if the PC does not send a usable md5.
                return Pair(true, "")
            }
            if (localMd5.equals(remoteMd5, ignoreCase = true)) {
                Log.i(TAG, "md5 verified for ${file?.name}")
                Pair(true, "")
            } else {
                Log.w(TAG, "md5 mismatch for ${file?.name}: local=$localMd5 remote=$remoteMd5")
                Pair(false, "md5 verify fail")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cannot parse fileverify body: $body", e)
            Pair(true, "")
        }
    }

    fun onReport(body: String) {
        // The PC reports progress/actions; nothing needed to continue the transfer.
    }

    fun onTaskComplete(body: String) {
        completed.set(true)
        onFinished()
    }
}

/**
 * Foreground service that shares files with a Xiaomi PC:
 * creates a Wi-Fi hotspot, starts the HTTPS API 1.0 server and advertises the
 * legacy MiDrop packets over BLE until the PC has fetched everything.
 */
class PcSendService : android.app.Service() {
    private val TAG = "CatSharePc"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var server: PcHttpServer? = null
    private var advThreads = mutableListOf<Thread>()
    private var session: PcSendSession? = null

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val task = intent.getParcelableExtra<TaskInfo>("task")
        if (task == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(
            NotificationUtils.SENDER_FG_ID,
            buildNotification("准备互传", "正在与小米电脑建立连接…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        job = scope.launch { runSend(task) }
        return START_NOT_STICKY
    }

    private suspend fun runSend(task: TaskInfo) {
        val settings = AppSettings(this)
        if (!PcRootShell.isRootAvailable()) {
            Log.e(TAG, "root unavailable, cannot create hotspot")
            finish("无法创建热点", "PC 互传需要 Root 权限")
            return
        }
        val deviceName = settings.deviceName.ifBlank { "Android" }
        var nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 8) nameBytes = nameBytes.copyOf(8)
        val nameUnbroken = deviceName.toByteArray(Charsets.UTF_8).size <= 8

        val selfDeviceId = ((Random.nextInt(0x10000)) and 0xffff)
        val taskId = Random.nextInt(0x10000)
        val nonce = Random.nextInt(0x10000)
        val channel = 6
        val support2_0 = false
        val peerDeviceId = task.device.id.toIntOrNull(16) ?: 0

        val files = buildSharedFiles(task.files)
        if (files.isEmpty()) {
            finish("没有可发送的文件", null)
            return
        }
        val ssid = PcProtocol.hotspotSsid(selfDeviceId, support2_0)
        val psk = PcProtocol.hotspotPsk(selfDeviceId, nonce, PcProtocol.pskNameBytes(deviceName))
        if (psk == null) {
            finish("密码派生失败", null)
            return
        }
        updateNotification("正在创建热点", ssid)
        // Start the ability advertisement as early as possible, so the PC can list the phone.
        val abilityFlags = PcProtocol.AbilityFlags(
            a = 1, b = 0, c = 0, d = 1, e = if (nameUnbroken) 1 else 0,
            f = 0, g = 0, h = 1, i = 0, j = 0,
        )
        val ability = PcProtocol.abilityPacket(deviceName, selfDeviceId, abilityFlags)
        advThreads.add(
            PcBle.advertiseSequence(this, "ability", listOf(ability), durationMs = 1500)
        )
        val apIp = PcHotspot.start(ssid, psk, "2")
        if (apIp == null) {
            finish("热点创建失败", "请检查 Root 权限")
            return
        }
        Log.i(TAG, "hotspot ready, ap ip=$apIp")
        val ipLastTwo = apIp.split(".").drop(2).mapNotNull { it.toIntOrNull() }.toIntArray()
        if (ipLastTwo.size < 2) {
            finish("热点地址异常", apIp)
            return
        }

        val done = AtomicBoolean(false)
        val session = PcSendSession(
            taskId = taskId,
            peerDeviceId = peerDeviceId,
            files = files,
            onProgress = { processed ->
                val total = files.sumOf { it.size }
                updateNotification(
                    "正在传输",
                    "${processed}/${total}".let { it }
                )
            },
            onFinished = { done.set(true) }
        )
        this.session = session

        // MD5s must be ready before /api/1.0/fileinfo is answered.
        files.forEach { f -> f.md5 = PcHttpServer.md5(f.file) }

        val server = PcHttpServer(this, session)
        if (!server.start()) {
            finish("HTTPS 服务启动失败", null)
            return
        }
        this.server = server
        updateNotification("等待电脑连接", ssid)

        val totalBytes = files.sumOf { it.size }
        val serverReady = PcProtocol.serverReadyPacket(
            selfDeviceId = selfDeviceId,
            peerDeviceId = peerDeviceId,
            flag = false,
            taskId = taskId,
            ipLastTwo = ipLastTwo,
        )
        val transfer = PcProtocol.transferControlPacket(
            selfDeviceId = selfDeviceId,
            peerDeviceId = peerDeviceId,
            support2_0 = support2_0,
            fileCount = files.size,
            channel = channel,
            taskId = taskId,
            nonce = nonce,
            nameBytes = PcProtocol.pskNameBytes(deviceName),
            ipLastTwo = ipLastTwo,
            sdkInt = android.os.Build.VERSION.SDK_INT,
        )
        advThreads.add(
            PcBle.advertiseSequence(
                this, "pc", listOf(transfer, serverReady, ability), durationMs = 1500
            )
        )

        // Wait until the PC completes the task (or timeout).
        var waited = 0L
        val timeoutMs = 5 * 60 * 1000L
        while (!done.get() && waited < timeoutMs) {
            delay(1000)
            waited += 1000
            if (waited % 15_000 == 0L) {
                Log.i(TAG, "still waiting for PC, processed=${session.processedBytes.get()}/$totalBytes")
            }
        }
        if (done.get()) {
            finish("传输完成", "已发送 ${files.size} 个文件")
        } else {
            finish("传输超时", "电脑没有完成接收")
        }
    }

    private fun buildSharedFiles(files: List<FileInfo>): List<PcSharedFile> {
        val out = ArrayList<PcSharedFile>()
        files.forEachIndexed { index, info ->
            val file = if (!info.textContent.isNullOrEmpty()) {
                try {
                    val dir = File(cacheDir, "pc_send")
                    dir.mkdirs()
                    File(dir, "sharedText.txt").apply {
                        writeText(info.textContent)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "cannot write shared text", e)
                    null
                }
            } else {
                uriToTempFile(info.uri, info.name)
            } ?: return@forEachIndexed
            val mimeCode = when {
                info.mimeType.startsWith("image/") -> 1
                info.mimeType.startsWith("video/") -> 2
                info.mimeType.startsWith("audio/") -> 3
                else -> 0
            }
            out.add(
                PcSharedFile(
                    id = index.toString(),
                    name = file.name,
                    mimeType = if (info.mimeType.isEmpty()) "text/plain" else info.mimeType,
                    size = file.length(),
                    file = file,
                    mimeTypeCode = mimeCode,
                )
            )
        }
        return out
    }

    private fun uriToTempFile(uri: Uri, name: String): File? {
        return try {
            val dir = File(cacheDir, "pc_send")
            dir.mkdirs()
            val out = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            out
        } catch (e: Throwable) {
            Log.e(TAG, "cannot open shared uri $uri", e)
            null
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        return NotificationCompat.Builder(this, NotificationUtils.SENDER_CHAN_ID)
            .setSmallIcon(moe.reimu.catshare.R.drawable.ic_upload_file)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        try {
            NotificationManagerCompat.from(this)
                .notify(NotificationUtils.SENDER_FG_ID, buildNotification(title, text))
        } catch (e: Throwable) {
            Log.e(TAG, "update notification failed", e)
        }
    }

    private fun finish(title: String, text: String?) {
        updateNotification(title, text ?: "")
        cleanUp()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanUp() {
        advThreads.forEach { it.interrupt() }
        advThreads.clear()
        server?.stop()
        server = null
        PcHotspot.stop()
        job?.cancel()
    }

    override fun onDestroy() {
        cleanUp()
        super.onDestroy()
    }

    companion object {
        fun getIntent(context: Context, task: TaskInfo): Intent =
            Intent(context, PcSendService::class.java).apply { putExtra("task", task) }

        fun start(context: Context, task: TaskInfo) {
            context.startForegroundService(getIntent(context, task))
        }
    }
}
