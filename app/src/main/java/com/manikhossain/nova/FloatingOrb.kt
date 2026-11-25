package com.manikhossain.nova

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small animated "wave" orb that floats over other apps while Nova is active. Tap = stop / talk. Drag to move. */
@SuppressLint("StaticFieldLeak")
object FloatingOrb {
    private var view: OrbView? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile var level = 0f
    @Volatile var state = "listening"

    fun show(c: Context) = main.post {
        if (view != null || !Prefs.floatingOrb || !Settings.canDrawOverlays(c)) return@post
        val wm = c.getSystemService(WindowManager::class.java)
        val size = (72 * c.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = Prefs.orbX.takeIf { it >= 0 } ?: (c.resources.displayMetrics.widthPixels - size - 24)
        lp.y = Prefs.orbY.takeIf { it >= 0 } ?: (c.resources.displayMetrics.heightPixels / 3)
        val v = OrbView(c, wm, lp)
        v.alpha = Prefs.orbOpacity / 100f
        try { wm.addView(v, lp); view = v } catch (_: Exception) {}
    }

    fun hide() = main.post {
        val v = view ?: return@post
        view = null
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
    }

    fun setOpacity(p: Int) = main.post { view?.alpha = p / 100f }

    private class OrbView(c: Context, val wm: WindowManager, val lp: WindowManager.LayoutParams) : View(c) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val t0 = System.currentTimeMillis()
        private var amp = 0f
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var moved = false

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val r = min(w, h) / 2f * 0.92f
            val t = (System.currentTimeMillis() - t0) / 1000f
            val col = when (state) { "speaking" -> Color.rgb(154, 243, 255); "thinking", "working" -> Color.rgb(255, 181, 71)
                "connecting" -> Color.rgb(139, 107, 255); else -> Color.rgb(61, 255, 168) }
            val target = when (state) { "speaking" -> 0.35f + level * 1.4f; "listening" -> 0.15f + level * 1.4f; else -> 0.3f }
            amp += (min(target, 1.2f) - amp) * 0.2f
            level *= 0.9f
            cv.save(); cv.translate(w / 2, h / 2)
            fill.shader = RadialGradient(0f, 0f, r, intArrayOf(Color.argb(230, 2, 5, 11), Color.argb(200, 2, 5, 11), Color.argb(0, 2, 5, 11)),
                floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(0f, 0f, r, fill)
            for (layer in 0..2) {
                path.reset()
                for (i in 0..96) {
                    val a = i / 96f * 6.2832f
                    val n = sin(a * (4 + layer * 2) + t * (3 + layer) * (if (layer == 1) -1 else 1)) * 0.6f + sin(a * 3 - t * 2) * 0.4f
                    val rr = r * (0.72f - layer * 0.16f + (0.03f + amp * 0.12f) * n)
                    val x = cos(a) * rr; val y = sin(a) * rr
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                ring.color = if (layer == 1) Color.argb(190, 139, 107, 255) else Color.argb(240 - layer * 50, Color.red(col), Color.green(col), Color.blue(col))
                ring.strokeWidth = if (layer == 0) r * 0.07f else r * 0.045f
                cv.drawPath(path, ring)
            }
            fill.shader = RadialGradient(0f, 0f, r * 0.3f, intArrayOf(Color.WHITE, col, Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(0f, 0f, r * (0.22f + amp * 0.06f), fill)
            cv.restore()
            postInvalidateOnAnimation()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) + abs(dy) > 12) moved = true
                    if (moved) { lp.x = startX + dx.toInt(); lp.y = startY + dy.toInt(); try { wm.updateViewLayout(this, lp) } catch (_: Exception) {} }
                }
                MotionEvent.ACTION_UP -> if (moved) { Prefs.orbX = lp.x; Prefs.orbY = lp.y } else Nova.toggle()
            }
            return true
        }
    }
}
