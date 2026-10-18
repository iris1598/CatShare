package moe.reimu.catshare.pc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Trampoline activity: starts [PcReceiveService] from a foreground context
 * (required by Android 12+ for starting foreground services) and finishes.
 *
 * Debug/testing usage:
 *   adb shell am start -n moe.reimu.catshare.debug/moe.reimu.catshare.pc.PcReceiveActivity \
 *       --es server_base https://127.0.0.1:9999 --ei peer_device_id 1234 --ei task_id 42
 */
class PcReceiveActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PcReceiveService.start(
            this,
            serverBase = intent.getStringExtra("server_base"),
            peerDeviceId = intent.getIntExtra("peer_device_id", 0),
            taskId = intent.getIntExtra("task_id", 0),
        )
        finish()
    }
}
