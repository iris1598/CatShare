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

        Thread {
            var percent = 0
            repeat(14) {
                nm.notify(
                    progressId,
                    MiuiFocusNotification.buildNotification(
                        this,
                        NotificationUtils.RECEIVER_CHAN_ID,
                        progressId,
                        "正在接收",
                        "${percent}% | 2.4 MB / 3.4 MB",
                        percent,
                        ongoing = true,
                    )
                )
                Thread.sleep(2000)
                percent = (percent + 7).coerceAtMost(100)
            }
        }.apply { isDaemon = true }.start()
        finish()
    }
}
