package io.github.timelock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Серце блокувальника. Android повідомляє службу, коли на екрані з'являється
 * інший додаток. Якщо ліміт для нього вичерпано — повертаємо на головний екран
 * і показуємо екран блокування. Поки додаток відкритий, раз на кілька секунд
 * перевіряємо, чи не скінчився час.
 */
class LimitService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }

    private var lastBlockAt = 0L
    private var lastGuardCheckAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            checkForeground(currentPackage())
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkForeground(pkg)
                checkGuard(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> checkGuard(pkg, force = false)
        }
    }

    private fun currentPackage(): String? =
        try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }

    private fun checkForeground(pkg: String?) {
        if (pkg == null || pkg == packageName) return
        val limitMin = prefs.activeLimits()[pkg] ?: return
        val usedMs = UsageTracker.usageToday(this, setOf(pkg), pkg)[pkg] ?: 0L
        if (usedMs >= limitMin * 60_000L) block(pkg)
    }

    private fun block(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 1500) return
        lastBlockAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        val intent = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Навіть якщо екран не відкрився, додаток уже закрито кнопкою «Додому».
        }
    }

    /**
     * Захист від видалення: поки він увімкнений, екрани налаштувань,
     * де згадується LockTime (інформація про додаток, видалення,
     * вимкнення спец. можливостей), одразу закриваються.
     */
    private fun checkGuard(pkg: String, force: Boolean) {
        if (pkg !in GUARDED_PACKAGES) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastGuardCheckAt < 700) return
        lastGuardCheckAt = now
        if (!prefs.protectionEnabled()) return

        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        val hits = root.findAccessibilityNodeInfosByText(getString(R.string.app_name))
        if (hits.isNullOrEmpty()) return

        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 150)
        if (now - lastBlockAt > 3000) {
            lastBlockAt = now
            Toast.makeText(
                this,
                "Захист LockTime увімкнено. Зняти його можна в додатку — через 72 години.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val TICK_MS = 3000L

        private val GUARDED_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.android.vending"
        )
    }
}
