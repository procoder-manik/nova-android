package com.manikhossain.nova

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF02050B.toInt()
        window.navigationBarColor = 0xFF02050B.toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        web = WebView(this)
        web.setBackgroundColor(0xFF02050B.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/ui/index.html")
        setContentView(web)

        Nova.uiListener = { ev, data -> emit(ev, data) }
        askBasics()
        if (intent?.action == Intent.ACTION_ASSIST) web.postDelayed({ Nova.activate() }, 600)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_ASSIST) Nova.activate()
    }

    override fun onResume() {
        super.onResume()
        emit("refresh", JSONObject())
    }

    override fun onDestroy() {
        if (Nova.uiListener != null) Nova.uiListener = null
        super.onDestroy()
    }

    private fun emit(ev: String, data: JSONObject) {
        val js = "window.nova && nova.on(${JSONObject.quote(ev)}, ${data})"
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    private fun askBasics() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1) else NovaService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        NovaService.start(this)
        emit("refresh", JSONObject())
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Gmail.REQ) {
            val ok = Gmail.onResult(this, data)
            emit("info", JSONObject().put("text", if (ok) "Gmail connected ✔" else "Gmail wasn't connected."))
            emit("refresh", JSONObject())
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    inner class Bridge {
        @JavascriptInterface fun toggle() = runOnUiThread { Nova.toggle() }
        @JavascriptInterface fun send(text: String) = runOnUiThread { Nova.sendText(text) }
        @JavascriptInterface fun stop() = runOnUiThread { Nova.deactivate() }

        @JavascriptInterface
        fun getState(): String {
            val pm = getSystemService(PowerManager::class.java)
            return JSONObject()
                .put("state", Nova.state).put("text", Nova.stateText)
                .put("has_key", Prefs.apiKey.isNotBlank()).put("user_name", Prefs.userName)
                .put("voice", Prefs.voice).put("female", JSONArray(Nova.FEMALE)).put("male", JSONArray(Nova.MALE))
                .put("wake_word", Prefs.wakeWord).put("wake_status", Nova.wakeStatus)
                .put("gmail", Prefs.gmailConnected).put("has_notion", Prefs.notionToken.isNotBlank())
                .put("active_seconds", Prefs.activeSeconds).put("mic_mode", Prefs.micMode)
                .put("floating_orb", Prefs.floatingOrb).put("orb_opacity", Prefs.orbOpacity)
                .put("screen_off_listen", Prefs.screenOffListen)
                .put("memories", Prefs.memories())
                .put("history", Prefs.historyJson())
                .put("perms", JSONObject()
                    .put("mic", granted(Manifest.permission.RECORD_AUDIO))
                    .put("contacts", granted(Manifest.permission.READ_CONTACTS))
                    .put("calls", granted(Manifest.permission.CALL_PHONE))
                    .put("calendar", granted(Manifest.permission.READ_CALENDAR))
                    .put("access", NovaAccess.enabled(this@MainActivity))
                    .put("notifications", NotifListener.enabled(this@MainActivity))
                    .put("overlay", Settings.canDrawOverlays(this@MainActivity))
                    .put("battery", pm.isIgnoringBatteryOptimizations(packageName)))
                .toString()
        }

        @JavascriptInterface
        fun save(json: String) {
            val s = JSONObject(json)
            if (s.optString("api_key").isNotBlank()) Prefs.apiKey = s.optString("api_key")
            if (s.optString("notion_token").isNotBlank()) Prefs.notionToken = s.optString("notion_token")
            if (s.has("user_name")) Prefs.userName = s.optString("user_name")
            if (s.optString("voice").isNotBlank()) Prefs.voice = s.optString("voice")
            if (s.has("wake_word") && s.optBoolean("wake_word") != Prefs.wakeWord) Nova.setWake(s.optBoolean("wake_word"))
            if (s.has("active_seconds")) Prefs.activeSeconds = s.optInt("active_seconds", 15)
            if (s.has("mic_mode")) Prefs.micMode = s.optString("mic_mode", "near")
            if (s.has("floating_orb")) { Prefs.floatingOrb = s.optBoolean("floating_orb"); if (!Prefs.floatingOrb) FloatingOrb.hide() }
            if (s.has("orb_opacity")) { Prefs.orbOpacity = s.optInt("orb_opacity", 85); FloatingOrb.setOpacity(Prefs.orbOpacity) }
            if (s.has("screen_off_listen")) Prefs.screenOffListen = s.optBoolean("screen_off_listen")
            runOnUiThread { NovaService.start(this@MainActivity); emit("refresh", JSONObject()) }
        }

        @JavascriptInterface
        fun grant(what: String) = runOnUiThread {
            when (what) {
                "mic" -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                "contacts" -> requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
                "calls" -> requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 3)
                "calendar" -> requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 4)
                "access" -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                "restricted" -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                "notifications" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                "battery" -> try {
                    @SuppressLint("BatteryLife")
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                    startActivity(i)
                } catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                "assistant" -> startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
        }

        @JavascriptInterface
        fun connectGmail() = runOnUiThread {
            Gmail.connect(this@MainActivity) { ok, msg ->
                emit("info", JSONObject().put("text", if (ok) "Gmail connected ✔" else "Gmail: $msg"))
                emit("refresh", JSONObject())
            }
        }

        @JavascriptInterface fun forget(fact: String) { Prefs.forget(fact) }
        @JavascriptInterface fun openUrl(u: String) = runOnUiThread { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (_: Exception) {} }
        @JavascriptInterface fun copy(t: String) = runOnUiThread {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nova", t))
        }
    }
}
