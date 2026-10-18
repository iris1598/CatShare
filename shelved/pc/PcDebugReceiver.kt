package moe.reimu.catshare.pc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import moe.reimu.catshare.models.DiscoveredDevice
import moe.reimu.catshare.models.FileInfo
import moe.reimu.catshare.models.TaskInfo
import kotlin.random.Random

/**
 * Debug-only trigger used to exercise the PC sharing path without UI interaction:
 *
 *   adb shell am broadcast -a moe.reimu.catshare.debug.PC_SEND_TEST \
 *       -n moe.reimu.catshare.debug/moe.reimu.catshare.pc.PcDebugReceiver \
 *       --es text "hello"
 */
class PcDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "CatShare PC test ${System.currentTimeMillis()}"
        val peerId = intent.getStringExtra("peer") ?: "0000"
        val bt = PcBle.adapter(context)
        val device = try {
            bt?.getRemoteDevice("00:00:00:00:00:00")
        } catch (e: Throwable) {
            null
        } ?: return
        val fileInfo = FileInfo(Uri.EMPTY, "sharedText.txt", "text/plain", text.toByteArray().size, text)
        val task = TaskInfo(
            id = Random.nextInt(),
            device = DiscoveredDevice(device, peerId, "小米电脑", "Xiaomi PC", false),
            files = listOf(fileInfo),
        )
        Log.i("CatSharePc", "debug broadcast: starting PC send, peer=$peerId")
        PcSendService.start(context, task)
    }
}
