package moe.reimu.catshare

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Receives taps on the test island buttons (see FocusTestActivity). */
class FocusTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("CatShareFocus", "island button tapped: ${intent.action}")
    }
}
