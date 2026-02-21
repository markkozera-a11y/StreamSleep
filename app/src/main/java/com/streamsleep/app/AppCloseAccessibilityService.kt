package com.streamsleep.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppCloseAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_CLOSE_APP = "com.streamsleep.CLOSE_APP"
        const val ACTION_SLEEP_SCREEN = "com.streamsleep.SLEEP_SCREEN"
        const val EXTRA_PACKAGE = "package_name"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CLOSE_APP -> {
                    val pkg = intent.getStringExtra(EXTRA_PACKAGE)
                    Log.d("StreamSleep", "Closing app: $pkg")
                    // Naciśnij przycisk Home, następnie wyczyść z ostatnich
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    performGlobalAction(GLOBAL_ACTION_RECENTS)
                    // Krótkie opóźnienie, potem zamknij
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        // Wróć do Home - aplikacja zostanie minimalizowana i wyrzucona
                        performGlobalAction(GLOBAL_ACTION_HOME)
                    }, 800)
                }
                ACTION_SLEEP_SCREEN -> {
                    Log.d("StreamSleep", "Locking screen")
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter().apply {
            addAction(ACTION_CLOSE_APP)
            addAction(ACTION_SLEEP_SCREEN)
        }
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        Log.d("StreamSleep", "AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (e: Exception) {}
    }
}
