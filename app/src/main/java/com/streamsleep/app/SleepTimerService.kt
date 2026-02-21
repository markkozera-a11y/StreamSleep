package com.streamsleep.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class SleepTimerService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_APP_PACKAGE = "EXTRA_APP_PACKAGE"
        const val EXTRA_APP_NAME = "EXTRA_APP_NAME"
        const val EXTRA_MINUTES = "EXTRA_MINUTES"

        const val CHANNEL_ID = "sleep_timer_channel"
        const val NOTIFICATION_ID = 1

        var isRunning = false
        var remainingSeconds = 0L
    }

    private var countdownTimer: CountDownTimer? = null
    private lateinit var overlayManager: OverlayTimerView

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        overlayManager = OverlayTimerView(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val packageName = intent.getStringExtra(EXTRA_APP_PACKAGE) ?: return START_NOT_STICKY
                val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "aplikacja"
                val minutes = intent.getIntExtra(EXTRA_MINUTES, 30)
                startTimer(packageName, appName, minutes)
            }
            ACTION_STOP -> stopTimer()
        }
        return START_NOT_STICKY
    }

    private fun startTimer(targetPackage: String, appName: String, minutes: Int) {
        stopTimer()
        isRunning = true
        val totalMs = minutes * 60 * 1000L
        remainingSeconds = minutes * 60L

        val notification = buildNotification(appName, minutes * 60L)
        startForeground(NOTIFICATION_ID, notification)

        overlayManager.show(remainingSeconds)

        countdownTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = millisUntilFinished / 1000
                overlayManager.updateTime(remainingSeconds)
                updateNotification(appName, remainingSeconds)
            }

            override fun onFinish() {
                remainingSeconds = 0
                overlayManager.hide()
                closeStreamingApp(targetPackage)
                turnOffScreen()
                stopSelf()
            }
        }.start()
    }

    private fun stopTimer() {
        countdownTimer?.cancel()
        countdownTimer = null
        isRunning = false
        overlayManager.hide()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun closeStreamingApp(packageName: String) {
        // Wysyłamy broadcast do AccesibilityService aby zamknął aplikację
        val intent = Intent(AppCloseAccessibilityService.ACTION_CLOSE_APP).apply {
            putExtra(AppCloseAccessibilityService.EXTRA_PACKAGE, packageName)
        }
        sendBroadcast(intent)
    }

    private fun turnOffScreen() {
        // Wymaga uprawnienia DEVICE_POWER lub AccessibilityService
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Tryb sleep przez AccessibilityService (Global Action)
        val intent = Intent(AppCloseAccessibilityService.ACTION_SLEEP_SCREEN)
        sendBroadcast(intent)
    }

    private fun buildNotification(appName: String, secondsLeft: Long): Notification {
        val stopIntent = Intent(this, SleepTimerService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamSleep aktywny")
            .setContentText("$appName zostanie zamknięty za ${formatTime(secondsLeft)}")
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Zatrzymaj", stopPendingIntent)
            .build()
    }

    private fun updateNotification(appName: String, secondsLeft: Long) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(appName, secondsLeft))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Timer snu", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Powiadomienie timera StreamSleep"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun formatTime(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%02d:%02d".format(m, s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopTimer()
    }
}
