package com.manikhossain.nova

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets Nova see what is on the screen (text of any app) and use it: tap, type, scroll, go back.
 * The user turns it on once in Settings → Accessibility → Nova. Nova never types passwords.
 */
class NovaAccess : AccessibilityService() {

    companion object {
        @Volatile var svc: NovaAccess? = null

        fun enabled(c: Context): Boolean {
            val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.contains(ComponentName(c, NovaAccess::class.java).flattenToString(), true) && svc != null
        }

        private fun off() = JSONObject().put("status", "not_enabled")
            .put("note", "Tell the user to turn on Nova in Settings → Accessibility (Nova → Settings → Permissions → 'See & use apps'). On Android 13+ first: Settings → Apps → Nova → ⋮ → Allow restricted settings.")

        fun readScreen(): JSONObject = svc?.read() ?: off()
        fun tap(target: String): JSONObject = svc?.tapText(target) ?: off()
        fun type(text: String, field: String, append: Boolean, enter: Boolean): JSONObject = svc?.typeText(text, field, append, enter) ?: off()
        fun scroll(dir: String): JSONObject = svc?.doScroll(dir) ?: off()
        fun global(action: String): JSONObject {
            val s = svc ?: return off()
            val a = when (action) {
                "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
                "notifications" -> GLOBAL_ACTION_NOTIFICATIONS; else -> return JSONObject().put("status", "error")
            }
            return JSONObject().put("status", if (s.performGlobalAction(a)) "done" else "failed")
        }
        fun screenshotJpeg(): ByteArray? = svc?.shot()
    }

    override fun onServiceConnected() { svc = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { svc = null; return super.onUnbind(intent) }
    override fun onDestroy() { svc = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun isPassword(n: AccessibilityNodeInfo) = n.isPassword

    // ------------------------------------------------------------------ reading
    private fun read(): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("status", "nothing_on_screen")
        val pkg = root.packageName?.toString() ?: ""
        val sb = StringBuilder()
        val seen = HashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || sb.length > 6000) return
            if (!n.isVisibleToUser) return
            val t = when {
                isPassword(n) -> "••••"
                !n.text.isNullOrBlank() -> n.text.toString()
                !n.contentDescription.isNullOrBlank() -> n.contentDescription.toString()
                else -> ""
            }.replace('\n', ' ').trim()
            if (t.isNotEmpty() && seen.add(t)) {
                val tag = when { n.isEditable -> "[input] "; n.isClickable -> "[button] "; else -> "" }
                sb.append(tag).append(t.take(300)).append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return JSONObject().put("app", appLabel(pkg)).put("package", pkg).put("screen_text", sb.toString().take(6000))
            .put("note", "Lines marked [button] can be tapped with tap(text); [input] fields can be typed into with type_text.")
    }

    // ------------------------------------------------------------------ acting
    private fun find(target: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = target.trim().lowercase()
        var best: AccessibilityNodeInfo? = null
        var score = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription ?: n.viewIdResourceName?.substringAfter('/') ?: "").toString().lowercase().trim()
                val sc = when { t.isEmpty() -> 0; t == q -> 100; t.startsWith(q) -> 70; t.contains(q) -> 50; else -> 0 }
                if (sc > score) { score = sc; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    private fun tapText(target: String): JSONObject {
        val n = find(target) ?: return JSONObject().put("status", "not_found").put("note", "Nothing called '$target' on screen. Call read_screen to see what is there.")
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return JSONObject().put("status", "tapped").put("target", target)
        val r = Rect(); n.getBoundsInScreen(r)
        return if (gestureTap(r.exactCenterX(), r.exactCenterY())) JSONObject().put("status", "tapped").put("target", target)
        else JSONObject().put("status", "failed")
    }

    private fun gestureTap(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null)
    }

    private fun typeText(text: String, field: String, append: Boolean, enter: Boolean): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("status", "nothing_on_screen")
        var target: AccessibilityNodeInfo? = if (field.isNotBlank()) find(field)?.takeIf { it.isEditable } else null
        if (target == null) target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        if (target == null) {
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 40 || target != null) return
                if (n.isEditable && n.isVisibleToUser) { target = n; return }
                for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
            }
            walk(root, 0)
        }
        val t = target ?: return JSONObject().put("status", "no_text_field").put("note", "No text box on screen. Tap the place to write first (e.g. 'New note', '+', 'Compose').")
        if (t.isPassword) return JSONObject().put("status", "refused").put("note", "Nova never types into password fields.")
        t.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val old = if (append) (t.text?.toString() ?: "").let { if (it == t.hintText?.toString()) "" else it } else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (old.isBlank()) text else "$old\n$text") }
        val ok = t.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && enter && Build.VERSION.SDK_INT >= 30) t.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        return JSONObject().put("status", if (ok) "typed" else "failed")
    }

    private fun doScroll(dir: String): JSONObject {
        val root = rootInActiveWindow ?: return JSONObject().put("status", "nothing_on_screen")
        var sc: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 40 || sc != null) return
            if (n.isScrollable && n.isVisibleToUser) { sc = n; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val action = if (dir == "up") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        if (sc?.performAction(action) == true) return JSONObject().put("status", "scrolled")
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val (y1, y2) = if (dir == "up") dm.heightPixels * 0.3f to dm.heightPixels * 0.75f else dm.heightPixels * 0.75f to dm.heightPixels * 0.3f
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val ok = dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 350)).build(), null, null)
        return JSONObject().put("status", if (ok) "scrolled" else "failed")
    }

    private fun shot(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        var out: ByteArray? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                    if (hw != null) {
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                        val scale = 960f / maxOf(sw.width, sw.height)
                        val small = Bitmap.createScaledBitmap(sw, (sw.width * scale).toInt(), (sw.height * scale).toInt(), true)
                        val bo = ByteArrayOutputStream()
                        small.compress(Bitmap.CompressFormat.JPEG, 70, bo)
                        out = bo.toByteArray()
                    }
                    r.hardwareBuffer.close()
                } catch (_: Exception) {}
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(4, TimeUnit.SECONDS)
        return out
    }
}
