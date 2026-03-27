package com.streamsleep.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class AppCloseAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_CLOSE_APP = "com.streamsleep.CLOSE_APP"
        const val ACTION_SLEEP_SCREEN = "com.streamsleep.SLEEP_SCREEN"
        const val EXTRA_PACKAGE = "package_name"

        private var instance: AppCloseAccessibilityService? = null

        fun closeApp(packageName: String) {
            instance?.doCloseApp(packageName)
        }

        fun lockScreen() {
            instance?.doLockScreen()
        }

        fun closeAppAndSleep(packageName: String) {
            instance?.doCloseAppAndSleep(packageName)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("StreamSleep", "AccessibilityService connected")
    }

    private fun doCloseApp(packageName: String) {
        Log.d("StreamSleep", "Closing app: $packageName")
        // Wracamy na ekran główny, żeby wyjść z Netflixa
        performGlobalAction(GLOBAL_ACTION_HOME)
        // Otwieramy ostatnie aplikacje i zamykamy wszystkie
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_RECENTS)
            handler.postDelayed({
                // Zamknij wszystkie ostatnie (dostępne od API 24)
                performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 800)
        }, 500)
    }

    private fun doLockScreen() {
        Log.d("StreamSleep", "Locking screen")
        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }

    private fun doCloseAppAndSleep(packageName: String) {
        Log.d("StreamSleep", "Closing app and going to sleep: $packageName")
        // 1. Wychodzimy na Home
        performGlobalAction(GLOBAL_ACTION_HOME)
        // 2. Po chwili blokujemy ekran (telefon przejdzie w tryb snu)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        }, 1500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
