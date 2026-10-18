package moe.reimu.catshare.utils

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import moe.reimu.catshare.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Xiaomi HyperOS "超级岛 / 焦点通知" integration.
 *
 * The format was recovered from the original Xiaomi 互传 app
 * (IslandNotificationHelper / FocusNotificationUtil):
 *
 *   extras["miui.focus.param"]   = {"param_v2": {...}, "business": "mishare", "param_island": {...}}
 *   extras["miui.focus.pics"]    = Bundle of Bitmap keyed by "miui.focus.pic_island"
 *   extras["miui.focus.actions"] = Bundle of Notification.Action keyed by action id
 *
 * Buttons are declared in param_v2.textButton as JSON objects whose "action" field
 * references an entry of the actions bundle (this is how the original receive
 * confirmation shows 接收 / 拒绝 inside the island).
 *
 * IMPORTANT: on HyperOS, third-party focus notifications are normally rejected by
 * SystemUI's online authorization (hyperos.developer.xiaomi.com, scope 20032). With
 * `persist.sys.feature.xms.switcher=true` (root; restart SystemUI) SystemUI takes the
 * "pass xms permission" branch and renders them. Without that switch only Xiaomi
 * signed apps can show a focus notification.
 */
object MiuiFocusNotification {
    private const val TAG = "CatShareFocus"
    private const val KEY_PARAM = "miui.focus.param"
    private const val KEY_PICS = "miui.focus.pics"
    private const val KEY_ACTIONS = "miui.focus.actions"
    private const val PIC_ISLAND = "miui.focus.pic_island"

    private val supported: Boolean by lazy { detectSupport() }

    fun isSupported(): Boolean = supported

    private fun detectSupport(): Boolean = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        val value = get.invoke(null, "persist.sys.feature.island") as? String
        val ok = value == "1" || value.equals("true", ignoreCase = true)
        Log.i(TAG, "HyperOS island support: $value")
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot detect HyperOS island support", e)
        false
    }

    /**
     * One button shown inside the island / focus notification.
     *
     * @param key action id referenced from param_v2.textButton, e.g. "miui.focus.action_confirm".
     */
    data class ActionSpec(
        val key: String,
        val title: String,
        val pendingIntent: PendingIntent,
        val iconRes: Int,
        val titleColor: String,
        val bgColor: String,
        val bgColorDark: String,
        val titleColorDark: String = "#FFFFFF",
    )

    /**
     * Applies the focus/island extras to [builder] and sets title/content.
     *
     * @param progressPercent 0..100 for an ongoing progress island, or null for a plain island.
     * @param actions buttons rendered inside the island (empty for a plain notification).
     */
    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        notificationId: Int,
        title: String,
        content: String,
        progressPercent: Int?,
        actions: List<ActionSpec> = emptyList(),
    ): NotificationCompat.Builder {
        if (!supported) return builder
        return try {
            val extras = buildExtras(context, notificationId, title, content, progressPercent, actions)
                ?: return builder
            builder.addExtras(extras)
                .setContentTitle(title)
                .setContentText(content)
            // Keep the normal shade actions in sync with the island buttons.
            actions.forEach { spec ->
                builder.addAction(spec.iconRes, spec.title, spec.pendingIntent)
            }
            builder
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply focus notification", e)
            builder
        }
    }

    private fun buildExtras(
        context: Context,
        notificationId: Int,
        title: String,
        content: String,
        progressPercent: Int?,
        actions: List<ActionSpec>,
    ): Bundle? {
        val notifyId = context.packageName + notificationId
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("notifyId", notifyId)
            .put("islandFirstFloat", true)
            .put("enableFloat", true)
            .put("timeout", 5)
            .put("updatable", true)
            .put("reopen", "reopen")
            .put("filterWhenNoPermission", false)
            .put("islandProperty", 1)
            .put("islandPriority", 1)
            .put("islandOrder", true)
            .put("islandTimeout", 10)
            .put("expandedTime", 5)
            .put("ticker", title)
            .put("aodTitle", content)
            .put(
                "iconTextInfo", JSONObject()
                    .put("title", title)
                    .put("content", content)
                    .put("colorTitle", "#CC000000")
                    .put("colorTitleDark", "#FFFFFF")
            )

        if (actions.isNotEmpty()) {
            paramV2.put(
                "baseInfo", JSONObject()
                    .put("type", 2)
                    .put("title", title)
                    .put("content", content)
            )
            paramV2.put("picInfo", picInfo(1, PIC_ISLAND).put("picDark", PIC_ISLAND))
            val textButton = JSONArray()
            actions.forEach { spec ->
                textButton.put(
                    JSONObject()
                        .put("actionTitle", spec.title)
                        .put("actionTitleColor", spec.titleColor)
                        .put("actionTitleColorDark", spec.titleColorDark)
                        .put("actionBgColor", spec.bgColor)
                        .put("actionBgColorDark", spec.bgColorDark)
                        .put("action", spec.key)
                )
            }
            paramV2.put("textButton", textButton)
        }

        val island = JSONObject()
        if (progressPercent != null) {
            // SystemUI's TemplateFactoryV3 picks the "moduleProgress" module when the island
            // area contains progressInfo with a non-negative progress and a non-empty
            // colorProgress. The old 互传 smali keys (colorReach / progressTextInfo /
            // combinePicInfo) are not understood by HyperOS 3, they leave area A empty.
            val progress = JSONObject()
                .put("progress", progressPercent.coerceIn(0, 100))
                .put("colorProgress", "#3482FF")
                .put("colorProgressDark", "#3482FF")
                .put("colorProgressEnd", "#33FFFFFF")
                .put("colorProgressEndDark", "#33FFFFFF")
                .put("isCCW", true)
            island.put(
                "smallIslandArea", JSONObject()
                    .put("picInfo", picInfo(1, PIC_ISLAND))
                    .put("progressInfo", progress)
            )
            island.put(
                "bigIslandArea", JSONObject()
                    .put("picInfo", picInfo(1, PIC_ISLAND))
                    .put("progressInfo", progress)
            )
        } else {
            island.put(
                "bigIslandArea", JSONObject()
                    .put("imageTextInfoLeft", imageTextInfoLeft(PIC_ISLAND))
                    .put(
                        "textInfo", JSONObject()
                            .put("title", title)
                            .put("showHighlightColor", true)
                    )
            )
            if (actions.isNotEmpty()) {
                island.put(
                    "smallIslandArea", JSONObject()
                        .put("picInfo", picInfo(1, PIC_ISLAND))
                )
            }
        }

        val param = JSONObject()
            .put("param_v2", paramV2)
            .put("business", "mishare")
            .put("param_island", island)

        val extras = Bundle()
        extras.putString(KEY_PARAM, param.toString())
        val pics = Bundle()
        iconBitmap(context)?.let { pics.putParcelable(PIC_ISLAND, it) }
        if (pics.size() > 0) {
            extras.putBundle(KEY_PICS, pics)
        }
        if (actions.isNotEmpty()) {
            val actionsBundle = Bundle()
            actions.forEach { spec ->
                val action = Notification.Action.Builder(
                    Icon.createWithResource(context, spec.iconRes),
                    spec.title,
                    spec.pendingIntent,
                ).build()
                actionsBundle.putParcelable(spec.key, action)
            }
            extras.putBundle(KEY_ACTIONS, actionsBundle)
        }
        return extras
    }

    private fun picInfo(type: Int, pic: String): JSONObject = JSONObject()
        .put("type", type)
        .put("pic", pic)

    private fun imageTextInfoLeft(pic: String): JSONObject = JSONObject()
        .put("type", 1)
        .put("picInfo", picInfo(4, pic))

    private fun iconBitmap(context: Context): Bitmap? = try {
        BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
    } catch (e: Throwable) {
        Log.w(TAG, "Cannot decode island bitmap", e)
        null
    }

    /** Builds a standalone notification (used by tests and by the receiver service). */
    fun buildNotification(
        context: Context,
        channelId: String,
        notificationId: Int,
        title: String,
        content: String,
        progressPercent: Int?,
        ongoing: Boolean,
        actions: List<ActionSpec> = emptyList(),
    ): Notification {
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_downloading)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
        apply(context, builder, notificationId, title, content, progressPercent, actions)
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent, false)
        }
        return builder.build()
    }
}
