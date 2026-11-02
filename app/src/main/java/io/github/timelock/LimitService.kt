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
 * інший додаток. Якщо для нього діє блок (вичерпано час, відкриття чи нічний
 * режим) — повертаємо на головний екран і показуємо екран блокування. Поки
 * додаток відкритий, раз на кілька секунд перевіряємо, чи не скінчився час.
 * У браузерах читаємо адресний рядок, щоб заблокувати і сайт заблокованого додатка.
 */
class LimitService : AccessibilityService() {

    enum class Reason { TIME, OPENS, NIGHT }

    private data class Verdict(val reason: Reason?, val remainingMs: Long?)

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }

    private var lastBlockAt = 0L
    private var lastGuardCheckAt = 0L
    private var lastBrowserCheckAt = 0L

    /** "пакет@день" -> найменший поріг (хв), про який уже попередили. */
    private val warned = HashMap<String, Int>()

    private val tick = object : Runnable {
        override fun run() {
            val pkg = currentPackage()
            checkForeground(pkg)
            if (pkg != null) checkBrowser(pkg, force = true)
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
                checkBrowser(pkg, force = true)
                checkGuard(pkg, force = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                checkBrowser(pkg, force = false)
                checkGuard(pkg, force = false)
            }
        }
    }

    private fun currentPackage(): String? =
        try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }

    // ---------- Перевірка додатків ----------

    /**
     * @param foreground додаток зараз на екрані: поточне відкриття вже зараховане,
     *        тож блокуємо, коли відкриттів стало більше за ліміт. Для сайту в браузері
     *        (додаток не на екрані) — коли ліміт уже вичерпано.
     */
    private fun evaluate(pkg: String, rule: Rule, foreground: Boolean): Verdict {
        if (rule.night && prefs.nightWindow().coversNow()) return Verdict(Reason.NIGHT, null)
        val limitMin = rule.minutesFor(LocalDate.now())
        val maxOpens = rule.opens
        if (limitMin == null && maxOpens == null) return Verdict(null, null)

        val usage = UsageTracker.usageToday(this, setOf(pkg), if (foreground) pkg else null)[pkg]
            ?: AppUsage()
        if (maxOpens != null) {
            val over = if (foreground) usage.opens > maxOpens else usage.opens >= maxOpens
            if (over) return Verdict(Reason.OPENS, null)
        }
        if (limitMin != null) {
            val remainingMs = limitMin * 60_000L - usage.timeMs
            return if (remainingMs <= 0L) Verdict(Reason.TIME, null) else Verdict(null, remainingMs)
        }
        return Verdict(null, null)
    }

    private fun checkForeground(pkg: String?) {
        if (pkg == null || pkg == packageName) return
        val rule = prefs.rules()[pkg] ?: return
        val verdict = evaluate(pkg, rule, foreground = true)
        val reason = verdict.reason
        if (reason != null) {
            block(pkg, reason, viaBrowser = false)
            return
        }
        val remaining = verdict.remainingMs ?: return
        val limitMin = rule.minutesFor(LocalDate.now()) ?: return
        maybeWarn(pkg, limitMin, remaining)
    }

    private fun block(pkg: String, reason: Reason, viaBrowser: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 1500) return
        lastBlockAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        val intent = Intent(this, BlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(BlockActivity.EXTRA_PACKAGE, pkg)
            .putExtra(BlockActivity.EXTRA_REASON, reason.name)
            .putExtra(BlockActivity.EXTRA_BROWSER, viaBrowser)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Навіть якщо екран не відкрився, додаток уже закрито кнопкою «Додому».
        }
    }

    // ---------- Сайти в браузері ----------

    /**
     * Читає адресний рядок браузера. Якщо відкрито сайт додатка, який зараз
     * заблоковано (наприклад, instagram.com, коли ліміт Instagram вичерпано), —
     * блокує браузер так само, як сам додаток.
     */
    private fun checkBrowser(pkg: String, force: Boolean) {
        val barIds = BROWSER_URL_BARS[pkg] ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastBrowserCheckAt < 1500) return
        lastBrowserCheckAt = now

        val rules = prefs.rules()
        if (rules.isEmpty()) return
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return
        if (root.packageName?.toString() != pkg) return

        var text: String? = null
        for (id in barIds) {
            val t = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull()?.text?.toString()
            } catch (e: Exception) {
                null
            }
            if (!t.isNullOrBlank()) {
                text = t
                break
            }
        }
        val host = hostOf(text ?: return) ?: return

        for ((domain, apps) in SITES) {
            if (host != domain && !host.endsWith(".$domain")) continue
            for (app in apps) {
                val rule = rules[app] ?: continue
                val reason = evaluate(app, rule, foreground = false).reason ?: continue
                block(app, reason, viaBrowser = true)
                return
            }
        }
    }

    private fun hostOf(text: String): String? {
        val t = text.trim().lowercase()
        if (t.isEmpty() || t.contains(' ')) return null // це пошуковий запит, а не адреса
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
        return if (host.contains('.')) host else null
    }

    // ---------- Попередження ----------

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

    // ---------- Захист від видалення ----------

    /**
     * Поки захист увімкнений, екрани налаштувань, де згадується LockTime
     * (інформація про додаток, видалення, вимкнення спец. можливостей),
     * одразу закриваються.
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

        /** Браузер -> id поля адресного рядка. */
        private val BROWSER_URL_BARS = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "com.sec.android.app.sbrowser.beta" to listOf("location_bar_edit_text"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.vivaldi.browser" to listOf("url_bar"),
            "com.opera.browser" to listOf("url_field"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput")
        )

        /** Сайт -> додатки, ліміт яких на нього поширюється. */
        private val SITES = mapOf(
            "instagram.com" to listOf("com.instagram.android", "com.instagram.lite"),
            "tiktok.com" to listOf(
                "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go"
            ),
            "youtube.com" to listOf("com.google.android.youtube"),
            "youtu.be" to listOf("com.google.android.youtube"),
            "facebook.com" to listOf("com.facebook.katana", "com.facebook.lite"),
            "x.com" to listOf("com.twitter.android"),
            "twitter.com" to listOf("com.twitter.android"),
            "reddit.com" to listOf("com.reddit.frontpage"),
            "threads.net" to listOf("com.instagram.barcelona"),
            "threads.com" to listOf("com.instagram.barcelona"),
            "pinterest.com" to listOf("com.pinterest")
        )
    }
}
