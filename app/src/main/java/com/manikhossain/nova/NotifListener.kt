package com.manikhossain.nova

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Keeps the latest notifications (WhatsApp, Gmail, Messenger, SMS, missed calls...) so Nova can read them out. */
class NotifListener : NotificationListenerService() {

    companion object {
        private val items = ArrayDeque<JSONObject>()
        private val skip = setOf("com.manikhossain.nova", "android", "com.android.systemui")

        fun enabled(c: Context): Boolean {
            val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(c, NotifListener::class.java).flattenToString())
        }

        @Synchronized
        fun add(o: JSONObject) {
            items.removeAll { it.optString("key") == o.optString("key") && it.optString("text") == o.optString("text") }
            items.addLast(o)
            while (items.size > 80) items.removeFirst()
        }

        @Synchronized
        fun recent(app: String, c: Context): JSONObject {
            if (!enabled(c)) return JSONObject().put("status", "not_enabled")
                .put("note", "Ask the user to turn on Notification access for Nova (Nova → Settings → Permissions).")
            val out = JSONArray()
            for (o in items.reversed()) {
                if (app.isNotBlank() && !o.optString("app").contains(app, true) && !o.optString("pkg").contains(app.replace(" ", ""), true)) continue
                out.put(JSONObject().put("app", o.optString("app")).put("from", o.optString("title"))
                    .put("text", o.optString("text")).put("minutes_ago", (System.currentTimeMillis() - o.optLong("t")) / 60000))
                if (out.length() >= 15) break
            }
            return JSONObject().put("notifications", out).put("count", out.length())
        }
    }

    override fun onListenerConnected() {
        try { activeNotifications?.forEach { capture(it) } } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = capture(sbn)

    private fun capture(sbn: StatusBarNotification) {
        if (sbn.packageName in skip) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) { sbn.packageName }
        add(JSONObject().put("key", sbn.key).put("pkg", sbn.packageName).put("app", label)
            .put("title", title.take(80)).put("text", text.take(400)).put("t", sbn.postTime))
    }
}
