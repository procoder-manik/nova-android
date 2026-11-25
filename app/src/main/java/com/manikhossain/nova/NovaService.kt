package com.manikhossain.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps Nova alive in the background so "Nova" works with the screen off (shows a small notification). */
class NovaService : Service() {
    companion object {
        private const val CH = "nova_listen"
        private var instance: NovaService? = null

        fun start(c: Context) {
            if (!Nova.hasMic()) return
            try { c.startForegroundService(Intent(c, NovaService::class.java)) } catch (_: Exception) {}
        }

        fun update() { instance?.let { it.getSystemService(NotificationManager::class.java).notify(1, it.build()) } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Battery saver: with "listen while screen is off" turned off, the microphone stops when the screen goes dark. */
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> if (!Prefs.screenOffListen && !Nova.active) Nova.stopMic()
                Intent.ACTION_SCREEN_ON -> Nova.startMic()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screen, f)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Nova listening", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "talk" -> Nova.toggle()
            "off" -> { Nova.deactivate(); Nova.stopMic(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, build())
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        Nova.startMic()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        try { unregisterReceiver(screen) } catch (_: Exception) {}
        super.onDestroy()
    }

    fun build(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val talk = PendingIntent.getService(this, 1, Intent(this, NovaService::class.java).setAction("talk"), flags)
        val off = PendingIntent.getService(this, 2, Intent(this, NovaService::class.java).setAction("off"), flags)
        val text = when (Nova.state) {
            "listening" -> "Listening…"; "speaking" -> "Speaking…"; "thinking", "working" -> "Working…"
            "connecting" -> "Connecting…"; else -> Nova.standbyText()
        }
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("NOVA").setContentText(text)
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (Nova.active) "Stop" else "Talk", talk).build())
            .addAction(Notification.Action.Builder(null, "Turn off", off).build())
            .build()
    }
}
