package moe.reimu.catshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import moe.reimu.catshare.utils.MiuiFocusNotification
import moe.reimu.catshare.utils.NotificationUtils

/**
 * Hidden test activity used to verify the HyperOS Super Island integration without a peer.
 *
 *   adb shell su -c 'am start -n moe.reimu.catshare/.FocusTestActivity'
 *
 * Requires `persist.sys.feature.xms.switcher=true` (root, then restart SystemUI).
 */
class FocusTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nm = NotificationManagerCompat.from(this)
        val progressId = 2001
        val resultId = 2002

        nm.notify(
            progressId,
            MiuiFocusNotification.buildNotification(
                this,
                NotificationUtils.RECEIVER_CHAN_ID,
                progressId,
                "正在接收",
                "1.2 MB / 3.4 MB | 35%",
                35,
                ongoing = true,
            )
        )

        Thread {
            Thread.sleep(2500)
            nm.notify(
                progressId,
                MiuiFocusNotification.buildNotification(
                    this,
                    NotificationUtils.RECEIVER_CHAN_ID,
                    progressId,
                    "正在接收",
                    "2.4 MB / 3.4 MB | 70%",
                    70,
                    ongoing = true,
                )
            )
            Thread.sleep(2500)
            nm.notify(
                resultId,
                MiuiFocusNotification.buildNotification(
                    this,
                    NotificationUtils.RECEIVER_CHAN_ID,
                    resultId,
                    "接收完成",
                    "已接收 2 个文件",
                    null,
                    ongoing = false,
                )
            )
        }.apply { isDaemon = true }.start()
        finish()
    }
}
