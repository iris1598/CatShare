package moe.reimu.catshare

import android.content.Context
import android.content.SharedPreferences

class AppSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString(
            "deviceName",
            context.getString(R.string.device_name_default_value)
        )!!
        set(value) {
            prefs.edit().putString("deviceName", value).apply()
        }

    var verbose: Boolean
        get() = prefs.getBoolean("verbose", false)
        set(value) {
            prefs.edit().putBoolean("verbose", value).apply()
        }

    /**
     * Manually configured MAC address override, in the `aa:bb:cc:dd:ee:ff` format.
     *
     * Null (or an empty value) means automatic detection (root -> permission -> Shizuku).
     */
    var macAddress: String?
        get() = prefs.getString("macAddress", null)
        set(value) {
            val editor = prefs.edit()
            if (value.isNullOrBlank()) {
                editor.remove("macAddress")
            } else {
                editor.putString("macAddress", value)
            }
            editor.apply()
        }
}