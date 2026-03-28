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
        Log.d(TAG, "=== Starting close app & sleep sequence for: $packageName ===")

        // 1. Wychodzimy z aplikacji na Home
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Otwieramy Quick Settings
        handler.postDelayed({
            Log.d(TAG, "Step 2: Opening Quick Settings")
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

            // 3. Czekamy na załadowanie QS i szukamy kafelka trybów
            handler.postDelayed({
                tryActivateSleepMode(attempt = 1)
            }, 2000)
        }, 1000)
    }

    private fun tryActivateSleepMode(attempt: Int) {
        Log.d(TAG, "Step 3: Searching for sleep/mode tile (attempt $attempt)")
        dumpNodeTree() // debug - pokaż drzewo nodeów w logcat

        val clicked = findAndClickModeOrSleepTile()

        if (clicked) {
            Log.d(TAG, "Clicked a tile! Waiting for dialog or mode activation...")
            // Samsung może wyświetlić dialog "Wybierz tryb" z opcją "Sen"
            // Czekamy i szukamy "Sen" w dialogu
            handler.postDelayed({
                val dialogClicked = findAndClickInDialog()
                if (dialogClicked) {
                    Log.d(TAG, "Clicked 'Sen' in dialog!")
                }
                // Niezależnie od wyniku — zamykamy i blokujemy
                handler.postDelayed({
                    finishAndLockScreen()
                }, 2000)
            }, 1500)
        } else if (attempt == 1) {
            // Spróbuj rozwinąć pełny panel QS (drugi raz)
            Log.d(TAG, "Tile not found, expanding full QS panel...")
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                tryActivateSleepMode(attempt = 2)
            }, 2000)
        } else {
            Log.d(TAG, "Sleep mode tile not found after $attempt attempts, locking screen")
            finishAndLockScreen()
        }
    }

    private fun findAndClickModeOrSleepTile(): Boolean {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "rootInActiveWindow is null!")
            return false
        }

        // Szukaj kafelka Tryby/Modes lub bezpośrednio Sen/Sleep
        val searchTerms = listOf("Sen", "Sleep", "Tryby", "Modes", "Tryb Sen", "Sleep mode")
        for (term in searchTerms) {
            val nodes = root.findAccessibilityNodeInfosByText(term)
            if (nodes.isNullOrEmpty()) continue

            for (node in nodes) {
                val nodeText = node.text?.toString() ?: ""
                val nodeDesc = node.contentDescription?.toString() ?: ""
                val nodeClass = node.className?.toString() ?: ""
                Log.d(TAG, "Found by '$term': text='$nodeText', desc='$nodeDesc', class=$nodeClass, clickable=${node.isClickable}")

                // Unikaj fałszywych trafień (np. "Sensor" zamiast "Sen")
                if (!matchesSleepTerm(nodeText) && !matchesSleepTerm(nodeDesc)) {
                    continue
                }

                // Spróbuj kliknąć node lub jego rodziców
                if (clickNodeOrParent(node)) {
                    root.recycle()
                    return true
                }
            }
        }

        // Głębokie przeszukiwanie drzewa (fallback)
        Log.d(TAG, "findByText failed, trying deep tree search...")
        val found = deepSearchAndClick(root)
        root.recycle()
        return found
    }

    private fun matchesSleepTerm(text: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase()
        // Dokładne dopasowanie "sen" (nie "sensor", "sens" itp.)
        return lower == "sen" ||
                lower.startsWith("sen\n") ||
                lower.startsWith("sen,") ||
                lower.startsWith("sen ") ||
                lower.contains("tryb sen") ||
                lower == "sleep" ||
                lower.startsWith("sleep\n") ||
                lower.startsWith("sleep,") ||
                lower.startsWith("sleep ") ||
                lower.contains("sleep mode") ||
                lower == "tryby" ||
                lower == "modes"
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        // Kliknij sam node
        if (node.isClickable) {
            Log.d(TAG, "Clicking node directly")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        // Szukaj klikalnego rodzica (do 6 poziomów w górę)
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable) {
                Log.d(TAG, "Clicking parent at depth $depth, class=${parent.className}")
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            parent = parent.parent
            depth++
        }
        return false
    }

    private fun deepSearchAndClick(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (matchesSleepTerm(text) || matchesSleepTerm(desc)) {
            Log.d(TAG, "Deep search hit: text='${node.text}', desc='${node.contentDescription}'")
            if (clickNodeOrParent(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (deepSearchAndClick(child)) return true
        }
        return false
    }

    private fun findAndClickInDialog(): Boolean {
        // Po kliknięciu kafelka Samsung wyświetla dialog "Wybierz tryb" z opcją "Sen"
        val root = rootInActiveWindow ?: return false
        Log.d(TAG, "Searching for 'Sen' in mode selection dialog...")
        dumpNodeTree()

        val searchTerms = listOf("Sen", "Sleep")
        for (term in searchTerms) {
            val nodes = root.findAccessibilityNodeInfosByText(term)
            if (nodes.isNullOrEmpty()) continue

            for (node in nodes) {
                val nodeText = node.text?.toString() ?: ""
                val nodeDesc = node.contentDescription?.toString() ?: ""

                // W dialogu szukamy dokładnie "Sen" (bez "Sensor" itp.)
                if (nodeText.lowercase() == "sen" || nodeText.lowercase() == "sleep" ||
                    nodeDesc.lowercase() == "sen" || nodeDesc.lowercase() == "sleep") {
                    Log.d(TAG, "Found 'Sen' in dialog: text='$nodeText', clickable=${node.isClickable}")
                    if (clickNodeOrParent(node)) {
                        root.recycle()
                        return true
                    }
                }
            }
        }
        root.recycle()
        return false
    }

    private fun finishAndLockScreen() {
        Log.d(TAG, "Finishing: closing panels and locking screen")
        // Zamknij Quick Settings / dialogi
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
                handler.postDelayed({
                    Log.d(TAG, "Locking screen now")
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }, 500)
            }, 300)
        }, 300)
    }

    private fun dumpNodeTree() {
        val root = rootInActiveWindow ?: run {
            Log.d(TAG, "DUMP: rootInActiveWindow is null")
            return
        }
        Log.d(TAG, "=== NODE TREE DUMP ===")
        dumpNode(root, 0)
        Log.d(TAG, "=== END DUMP ===")
        root.recycle()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 8) return // limit głębokości
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            Log.d(TAG, "${indent}[${node.className}] text='$text' desc='$desc' click=${node.isClickable}")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
