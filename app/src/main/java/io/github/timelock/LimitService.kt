package io.github.timelock

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.time.LocalDate

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

    /** "пакет@день" -> найменший поріг (хв), про який уже попередили. */
    private val warned = HashMap<String, Int>()

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
        val remainingMs = limitMin * 60_000L - usedMs
        if (remainingMs <= 0L) block(pkg) else maybeWarn(pkg, limitMin, remainingMs)
    }

    /** Попередження за 15, 10 і 5 хвилин до кінця ліміту. */
    private fun maybeWarn(pkg: String, limitMin: Int, remainingMs: Long) {
        val key = pkg + "@" + LocalDate.now().toEpochDay()
        val alreadyWarned = warned[key] ?: Int.MAX_VALUE
        // Найменший поріг, який уже перетнули.
        val crossed = WARN_AT_MINUTES.filter { remainingMs <= it * 60_000L }.minOrNull() ?: return
        if (crossed >= alreadyWarned) return
        warned[key] = crossed
        // Не попереджаємо одразу при відкритті, якщо сам ліміт не більший за поріг
        // (наприклад, ліміт 10 хв — «залишилось 10 хв» було б зайвим).
        if (crossed >= limitMin) return
        showWarning(pkg, remainingMs)
    }

    private fun showWarning(pkg: String, remainingMs: Long) {
        val minutes = ((remainingMs + 59_999L) / 60_000L).toInt()
        val label = try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
        val title = "Залишилось $minutes хв"
        val text = "Скоро час у «$label» на сьогодні вийде"

        val nm = getSystemService(NotificationManager::class.java)
        if (nm == null || !nm.areNotificationsEnabled()) {
            Toast.makeText(this, "$title — $text", Toast.LENGTH_LONG).show()
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Попередження про ліміт", NotificationManager.IMPORTANCE_HIGH)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .build()
        try {
            nm.notify(pkg.hashCode(), notification)
        } catch (e: SecurityException) {
            Toast.makeText(this, "$title — $text", Toast.LENGTH_LONG).show()
        }
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
        private const val CHANNEL_ID = "limit_warnings"
        private val WARN_AT_MINUTES = listOf(15, 10, 5)

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
