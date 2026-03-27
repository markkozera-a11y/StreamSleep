package com.streamsleep.app

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AppCloseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamSleep"
        private var instance: AppCloseAccessibilityService? = null

        fun closeAppAndSleep(packageName: String) {
            instance?.doCloseAppAndSleep(packageName)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AccessibilityService connected")
    }

    private fun doCloseAppAndSleep(packageName: String) {
        Log.d(TAG, "Closing app and activating sleep mode: $packageName")

        // 1. Wychodzimy z aplikacji streamingowej na Home
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Otwieramy Quick Settings i szukamy kafelka "Sen"
        handler.postDelayed({
            activateSamsungSleepMode()
        }, 1000)
    }

    private fun activateSamsungSleepMode() {
        Log.d(TAG, "Opening Quick Settings to find Sleep mode tile")
        // Otwórz pełny panel Quick Settings (dwa razy = pełny panel)
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

        handler.postDelayed({
            val found = findAndClickSleepTile()
            if (found) {
                Log.d(TAG, "Sleep mode tile clicked!")
                // Zamknij panel Quick Settings i zablokuj ekran
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                    }, 500)
                }, 1500)
            } else {
                Log.d(TAG, "Sleep mode tile not found, trying expanded panel")
                // Spróbuj rozwinąć panel (przeciągnij w dół jeszcze raz)
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                handler.postDelayed({
                    val foundExpanded = findAndClickSleepTile()
                    if (foundExpanded) {
                        Log.d(TAG, "Sleep mode tile clicked in expanded panel!")
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            handler.postDelayed({
                                performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                            }, 500)
                        }, 1500)
                    } else {
                        Log.d(TAG, "Sleep mode tile not found at all, just locking screen")
                        // Fallback: zamknij panel i zablokuj ekran
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({
                            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                        }, 500)
                    }
                }, 1200)
            }
        }, 1200)
    }

    private fun findAndClickSleepTile(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // Szukaj kafelka po różnych wariantach nazwy (PL/EN)
        val searchTerms = listOf("Sen", "Sleep", "Tryb Sen", "Sleep mode")
        for (term in searchTerms) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(term)
            if (nodes != null && nodes.isNotEmpty()) {
                for (node in nodes) {
                    Log.d(TAG, "Found node: '${node.text}', class: ${node.className}")
                    // Kliknij sam node
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        rootNode.recycle()
                        return true
                    }
                    // Kliknij rodzica (kafelki QS często mają klikalnego rodzica)
                    var parent = node.parent
                    var depth = 0
                    while (parent != null && depth < 5) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            rootNode.recycle()
                            return true
                        }
                        parent = parent.parent
                        depth++
                    }
                }
            }
        }
        rootNode.recycle()
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
