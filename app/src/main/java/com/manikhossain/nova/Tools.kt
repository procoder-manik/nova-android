package com.manikhossain.nova

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything Nova can do on the phone. Each tool takes the model's JSON args and returns a JSON result. */
class Tools(private val ctx: Context) {

    // ------------------------------------------------------------------ declarations sent to Gemini
    companion object {
        private fun p(type: String, d: String) = JSONObject().put("type", type).put("description", d)
        private fun s(d: String) = p("STRING", d)
        private fun decl(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), req: List<String> = emptyList()): JSONObject {
            val o = JSONObject().put("name", name).put("description", desc)
            if (props.isNotEmpty()) {
                val pr = JSONObject()
                props.forEach { (k, v) -> pr.put(k, v) }
                o.put("parameters", JSONObject().put("type", "OBJECT").put("properties", pr).put("required", JSONArray(req)))
            }
            return o
        }

        fun declarations(): JSONArray = JSONArray(listOf(
            decl("open_app", "Open any installed app on the phone (WhatsApp, Gmail, YouTube, Facebook, Camera, Chrome, Settings...).", mapOf("name" to s("app name")), listOf("name")),
            decl("open_url", "Open a website in the browser.", mapOf("url" to s("web address")), listOf("url")),
            decl("search_web", "Show a Google search in the browser for the user to look at.", mapOf("query" to s("search words")), listOf("query")),
            decl("youtube", "Search / play on YouTube.", mapOf("query" to s("what to find")), listOf("query")),
            decl("find_contact", "Find a phone contact's number.", mapOf("name" to s("contact name")), listOf("name")),
            decl("make_call", "Phone call a contact or number.", mapOf("name_or_number" to s("contact name or number")), listOf("name_or_number")),
            decl("whatsapp_message", "Open WhatsApp chat with the message typed in, ready for the user to press send.", mapOf("name_or_number" to s("contact or number"), "message" to s("message text")), listOf("name_or_number", "message")),
            decl("sms_message", "Open the SMS app with the message typed in, ready for the user to press send.", mapOf("name_or_number" to s("contact or number"), "message" to s("message text")), listOf("name_or_number", "message")),
            decl("read_notifications", "Read the phone's recent notifications (new WhatsApp/Messenger/Gmail/SMS messages, missed calls...). Optionally filter by app.", mapOf("app" to s("optional app name, e.g. WhatsApp, Gmail"))),
            decl("check_email", "Read the user's Gmail inbox (sender, subject, snippet). Default: unread mail.", mapOf("query" to s("Gmail search, e.g. is:unread, from:bank, newer_than:2d"))),
            decl("calendar_events", "Read the user's calendar (meetings, events) from the phone. Default: today and tomorrow.", mapOf("days_ahead" to p("INTEGER", "how many days ahead, default 1"), "days_back" to p("INTEGER", "days in the past, default 0"), "query" to s("optional word to filter"))),
            decl("add_calendar_event", "Add an event/meeting to the user's calendar.", mapOf("title" to s("title"), "start" to s("start time as YYYY-MM-DD HH:mm (24h)"), "minutes" to p("INTEGER", "length in minutes, default 60"), "location" to s("optional"), "notes" to s("optional")), listOf("title", "start")),
            decl("read_screen", "Read the text currently on the phone screen (any app). Use after open_app to see what's inside an app (Calendar, Gmail, WhatsApp, Notes, Messenger...)."),
            decl("look_at_screen", "Take a screenshot and look at it (for pictures, layouts, or when read_screen isn't enough)."),
            decl("tap", "Tap a button/item on screen by its text or label (as seen in read_screen).", mapOf("target" to s("text or label of the thing to tap")), listOf("target")),
            decl("type_text", "Type text into a text box on screen (note, message, search box...).", mapOf("text" to s("text to type"), "field" to s("optional: label/hint of the box"), "append" to p("BOOLEAN", "add after existing text"), "press_enter" to p("BOOLEAN", "press enter/search after typing")), listOf("text")),
            decl("scroll", "Scroll the screen.", mapOf("direction" to s("down or up")), listOf("direction")),
            decl("phone_button", "Press back / home / recents, or open the notification shade.", mapOf("button" to s("back, home, recents, notifications")), listOf("button")),
            decl("notion_search", "Search the user's Notion pages.", mapOf("query" to s("words")), listOf("query")),
            decl("notion_create_page", "Create a Notion page (notes, summaries) with Markdown content.", mapOf("title" to s("page title"), "content" to s("markdown: # heading, - bullets, plain lines"), "parent" to s("optional parent page name")), listOf("title", "content")),
            decl("notion_append", "Add Markdown content to the end of an existing Notion page.", mapOf("page" to s("page name or id"), "content" to s("markdown")), listOf("page", "content")),
            decl("notion_read", "Read a Notion page's text.", mapOf("page" to s("page name or id")), listOf("page")),
            decl("set_alarm", "Set an alarm.", mapOf("hour" to p("INTEGER", "0-23"), "minute" to p("INTEGER", "0-59"), "label" to s("optional label")), listOf("hour", "minute")),
            decl("set_timer", "Start a countdown timer.", mapOf("seconds" to p("INTEGER", "length in seconds"), "label" to s("optional label")), listOf("seconds")),
            decl("flashlight", "Turn the flashlight (torch) on or off.", mapOf("on" to p("BOOLEAN", "true = on")), listOf("on")),
            decl("media_control", "Music/volume control.", mapOf("action" to s("play_pause, next, previous, volume_up, volume_down, mute")), listOf("action")),
            decl("navigate", "Open Google Maps directions to a place.", mapOf("destination" to s("place or address")), listOf("destination")),
            decl("phone_status", "Battery level, charging state, date and time."),
            decl("remember", "Save a fact, preference or correction about the user permanently.", mapOf("fact" to s("what to remember"), "category" to s("preference / personal / correction / goal")), listOf("fact")),
            decl("end_conversation", "Go to standby. FIRST say a short goodbye (they can call 'Nova' anytime), THEN call this. Use when the user says stop / bye / থামো / that's all."),
        ))
    }

    fun run(name: String, a: JSONObject): JSONObject = try {
        when (name) {
            "open_app" -> openApp(a.optString("name")).also { if (it.optString("status") == "opened") Thread.sleep(1200) }
            "open_url" -> openUrl(a.optString("url"))
            "search_web" -> openUrl("https://www.google.com/search?q=" + enc(a.optString("query")))
            "youtube" -> openUrl("https://www.youtube.com/results?search_query=" + enc(a.optString("query")))
            "find_contact" -> findContact(a.optString("name"))
            "make_call" -> call(a.optString("name_or_number"))
            "whatsapp_message" -> whatsapp(a.optString("name_or_number"), a.optString("message"))
            "sms_message" -> sms(a.optString("name_or_number"), a.optString("message"))
            "read_notifications" -> NotifListener.recent(a.optString("app"), ctx)
            "check_email" -> checkEmail(a.optString("query").ifBlank { "is:unread in:inbox" })
            "calendar_events" -> CalendarTool.events(ctx, a.optInt("days_ahead", 1), a.optInt("days_back", 0), a.optString("query"))
            "add_calendar_event" -> CalendarTool.add(ctx, a.optString("title"), a.optString("start"), a.optInt("minutes", 60), a.optString("location"), a.optString("notes"))
            "read_screen" -> NovaAccess.readScreen()
            "tap" -> NovaAccess.tap(a.optString("target")).also { Thread.sleep(700) }
            "type_text" -> NovaAccess.type(a.optString("text"), a.optString("field"), a.optBoolean("append"), a.optBoolean("press_enter"))
            "scroll" -> NovaAccess.scroll(a.optString("direction", "down")).also { Thread.sleep(500) }
            "phone_button" -> NovaAccess.global(a.optString("button")).also { Thread.sleep(600) }
            "notion_search" -> Notion.search(a.optString("query"))
            "notion_create_page" -> Notion.createPage(a.optString("title"), a.optString("content"), a.optString("parent"))
            "notion_append" -> Notion.append(a.optString("page"), a.optString("content"))
            "notion_read" -> Notion.read(a.optString("page"))
            "set_alarm" -> alarm(a.optInt("hour"), a.optInt("minute"), a.optString("label"))
            "set_timer" -> timer(a.optInt("seconds"), a.optString("label"))
            "flashlight" -> torch(a.optBoolean("on", true))
            "media_control" -> media(a.optString("action"))
            "navigate" -> start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + enc(a.optString("destination")))), "navigating")
            "phone_status" -> status()
            "remember" -> Prefs.remember(a.optString("fact"), a.optString("category", "general")).also { Nova.ui("memory", JSONObject().put("fact", a.optString("fact"))) }
            else -> JSONObject().put("status", "error").put("error", "unknown tool $name")
        }
    } catch (e: Exception) {
        JSONObject().put("status", "error").put("error", e.message ?: e.toString())
    }

    /** Gmail API if connected; otherwise open the Gmail app and read the inbox from the screen. */
    private fun checkEmail(q: String): JSONObject {
        val r = Gmail.check(ctx, q)
        if (r.optString("status") != "gmail_not_connected") return r
        if (NovaAccess.svc != null) {
            val o = openApp("gmail")
            if (o.optString("status") == "opened") {
                Thread.sleep(2500)
                return NovaAccess.readScreen().put("source", "Gmail app screen (inbox)")
                    .put("note", "This is the Gmail inbox as shown on screen. To open a mail, tap its subject, then read_screen.")
            }
        }
        return r
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun start(i: Intent, ok: String): JSONObject {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            val o = JSONObject().put("status", ok)
            val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
            if (km.isKeyguardLocked) o.put("note", "The phone is locked: it will appear after the user unlocks. Say so briefly.")
            if (!Settings.canDrawOverlays(ctx)) o.put("note", "If nothing opened, the user must allow 'Display over other apps' for Nova (Settings in Nova).")
            o
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", "No app can handle this: ${e.message}")
        }
    }

    private fun openUrl(url: String): JSONObject {
        val u = if (url.startsWith("http")) url else "https://$url"
        return start(Intent(Intent.ACTION_VIEW, Uri.parse(u)), "opened").put("url", u)
    }

    // ------------------------------------------------------------------ apps
    private val aliases = mapOf(
        "settings" to "__settings", "সেটিংস" to "__settings", "browser" to "chrome", "google" to "google",
        "facebook" to "facebook", "fb" to "facebook", "messenger" to "messenger", "mail" to "gmail", "email" to "gmail",
        "youtube" to "youtube", "whatsapp" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp", "camera" to "camera", "ক্যামেরা" to "camera",
        "gallery" to "gallery", "photos" to "photos", "maps" to "maps", "play store" to "play store", "bkash" to "bkash",
        "nagad" to "nagad", "calculator" to "calculator", "clock" to "clock", "phone" to "phone", "dialer" to "phone",
        "messages" to "messages", "notion" to "notion", "telegram" to "telegram", "chatgpt" to "chatgpt",
    )

    private fun openApp(name: String): JSONObject {
        var key = name.trim().lowercase(Locale.ROOT)
        aliases[key]?.let { key = it }
        if (key == "__settings") return start(Intent(Settings.ACTION_SETTINGS), "opened")
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var best: android.content.pm.ResolveInfo? = null
        var score = 0
        for (r in apps) {
            val label = r.loadLabel(pm).toString().lowercase(Locale.ROOT)
            val pkg = r.activityInfo.packageName.lowercase(Locale.ROOT)
            val sc = when {
                label == key -> 100
                label.startsWith(key) -> 80
                label.contains(key) -> 60
                pkg.contains(key.replace(" ", "")) -> 50
                key.contains(label) && label.length > 3 -> 40
                else -> 0
            }
            if (sc > score) { score = sc; best = r }
        }
        val b = best ?: return JSONObject().put("status", "not_found").put("note", "No app called '$name' on this phone. Ask the user for the exact name, or offer the website.")
        val i = pm.getLaunchIntentForPackage(b.activityInfo.packageName) ?: return JSONObject().put("status", "error")
        return start(i, "opened").put("app", b.loadLabel(pm).toString())
    }

    // ------------------------------------------------------------------ contacts / calls / messages
    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }

    private fun findContact(name: String): JSONObject {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return JSONObject().put("status", "no_permission").put("note", "Ask the user to allow Contacts for Nova (Nova → Settings → Permissions).")
        val out = JSONArray()
        val seen = HashSet<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { c ->
            while (c.moveToNext() && out.length() < 8) {
                val n = c.getString(0) ?: continue
                val num = digits(c.getString(1) ?: "")
                if (seen.add(num.takeLast(10))) out.put(JSONObject().put("name", n).put("number", num))
            }
        }
        return if (out.length() == 0) JSONObject().put("status", "not_found").put("note", "No contact matching '$name'. Ask for the number or the exact saved name.")
        else JSONObject().put("matches", out)
    }

    private fun resolve(nameOrNumber: String): Pair<String?, JSONObject?> {
        val d = digits(nameOrNumber)
        if (d.length >= 6) return d to null
        val r = findContact(nameOrNumber)
        val m = r.optJSONArray("matches") ?: return null to r
        if (m.length() > 1) {
            val first = m.getJSONObject(0)
            if (!first.optString("name").equals(nameOrNumber, true))
                return null to JSONObject().put("status", "ambiguous").put("matches", m).put("note", "Several contacts match. Ask which one.")
        }
        return m.getJSONObject(0).optString("number") to null
    }

    @SuppressLint("MissingPermission")
    private fun call(target: String): JSONObject {
        val (num, err) = resolve(target)
        if (num == null) return err ?: JSONObject().put("status", "not_found")
        val direct = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val i = Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$num"))
        return start(i, if (direct) "calling" else "dialer_opened").put("number", num)
    }

    private fun whatsapp(target: String, msg: String): JSONObject {
        val (num, err) = resolve(target)
        if (num == null) return err ?: JSONObject().put("status", "not_found")
        var n = num.removePrefix("+")
        if (n.startsWith("01") && n.length == 11) n = "88$n"            // Bangladesh local -> international
        val r = start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + enc(msg))).setPackage("com.whatsapp"), "opened_ready_to_send")
        if (r.optString("status") == "error") return start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + enc(msg))), "opened_ready_to_send")
        return r.put("note", "The message is typed in WhatsApp; tell the user to press send.")
    }

    private fun sms(target: String, msg: String): JSONObject {
        val (num, err) = resolve(target)
        if (num == null) return err ?: JSONObject().put("status", "not_found")
        return start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", msg), "opened_ready_to_send")
            .put("note", "The SMS is typed; tell the user to press send.")
    }

    // ------------------------------------------------------------------ phone functions
    private fun alarm(h: Int, m: Int, label: String): JSONObject = start(
        Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Nova" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "alarm_set"
    ).put("time", String.format(Locale.US, "%02d:%02d", h, m))

    private fun timer(sec: Int, label: String): JSONObject = start(
        Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Nova" }).putExtra(AlarmClock.EXTRA_SKIP_UI, true), "timer_started"
    )

    private fun torch(on: Boolean): JSONObject {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return JSONObject().put("status", "no_flash")
        cm.setTorchMode(id, on)
        return JSONObject().put("status", if (on) "on" else "off")
    }

    private fun media(action: String): JSONObject {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun key(code: Int) {
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        }
        when (action) {
            "play_pause" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume_up" -> repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            "volume_down" -> repeat(3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
            else -> return JSONObject().put("status", "error").put("error", "unknown action")
        }
        return JSONObject().put("status", "done")
    }

    private fun status(): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return JSONObject()
            .put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .put("now", SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date()))
    }
}
