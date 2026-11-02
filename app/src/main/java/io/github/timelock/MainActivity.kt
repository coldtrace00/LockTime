package io.github.timelock

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/**
 * Головний екран з трьома вкладками:
 *  «Сьогодні» — скільки лишилось, «Ліміти» — правила, «Налаштування» — усе інше.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout

    private var tab = TAB_TODAY
    private var allApps: List<AppItem> = emptyList()
    private var appsLoaded = false

    /** Стан перевірки оновлень, поки екран відкритий. */
    private var updateState: String? = null
    private var updateRelease: Updater.Release? = null
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Look.themeResNoBar(this))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        tab = savedInstanceState?.getInt(KEY_TAB, TAB_TODAY) ?: TAB_TODAY

        styleSystemBars()
        content = FrameLayout(this)
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Look.navBg(this@MainActivity))
            setPadding(0, dp(6), 0, dp(8))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.bg(this@MainActivity))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(Look.divider(this@MainActivity)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(nav)
        })

        Thread {
            val items = Apps.loadLaunchable(this)
            runOnUiThread {
                allApps = items
                appsLoaded = true
                render()
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, tab)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        window.statusBarColor = Look.bg(this)
        window.navigationBarColor = Look.navBg(this)
        if (!Look.isDark(this)) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    // =====================================================================
    // Навігація
    // =====================================================================

    private fun render() {
        renderNav()
        content.removeAllViews()
        val page = when (tab) {
            TAB_LIMITS -> limitsPage()
            TAB_SETTINGS -> settingsPage()
            else -> todayPage()
        }
        content.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        })
    }

    private fun showTab(t: Int) {
        if (t == tab) return
        tab = t
        render()
    }

    private fun renderNav() {
        nav.removeAllViews()
        val items = listOf(
            Triple(TAB_TODAY, R.drawable.ic_tab_today, R.string.tab_today),
            Triple(TAB_LIMITS, R.drawable.ic_tab_limits, R.string.tab_limits),
            Triple(TAB_SETTINGS, R.drawable.ic_tab_settings, R.string.tab_settings)
        )
        for ((t, icon, label) in items) {
            val on = t == tab
            val color = if (on) Look.accentText(this) else Look.muted(this)
            nav.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                // Значок у «пігулці» фіксованого розміру — по центру кнопки.
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(icon)
                    setColorFilter(color)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    if (on) background = Look.rounded(Look.accentSoft(this@MainActivity), dpf(16))
                    setPadding(0, dp(4), 0, dp(4))
                }, LinearLayout.LayoutParams(dp(60), dp(32)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                // Підпис — теж по центру і в один рядок, щоб не з'їжджав.
                addView(TextView(this@MainActivity).apply {
                    text = getString(label)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(color)
                    if (on) setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(2), dp(4), dp(2), 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener { showTab(t) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // =====================================================================
    // Вкладка «Сьогодні»
    // =====================================================================

    private fun todayPage(): View {
        val page = pageLayout(getString(R.string.tab_today))
        val rules = prefs.rules()

        if (!allPermissionsOk()) {
            page.addView(card().apply {
                background = Look.rounded(Look.accentSoft(this@MainActivity), dpf(16))
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.perm_warning_title)
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Look.text(this@MainActivity))
                })
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.perm_warning_text)
                    textSize = 13f
                    setTextColor(Look.accentText(this@MainActivity))
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { showTab(TAB_SETTINGS) }
            }, blockParams())
        }

        if (rules.isEmpty()) {
            page.addView(emptyState(), blockParams())
            return page
        }

        val usage = if (hasUsageAccess()) UsageTracker.usageToday(this, rules.keys, null) else emptyMap()
        val today = LocalDate.now()

        // Кільце: використаний час / сума денних лімітів (лише для додатків з лімітом часу).
        var usedMs = 0L
        var limitMs = 0L
        for ((pkg, rule) in rules) {
            val u = usage[pkg]?.timeMs ?: 0L
            usedMs += u
            rule.minutesFor(today)?.let { limitMs += it * 60_000L }
        }
        page.addView(FrameLayout(this).apply {
            val size = dp(170)
            addView(RingView(this@MainActivity,
                if (limitMs > 0) (usedMs.toFloat() / limitMs).coerceIn(0f, 1f) else 0f,
                if (limitMs > 0 && usedMs >= limitMs) Look.danger(this@MainActivity) else Look.ACCENT_FILL,
                Look.barEmpty(this@MainActivity)
            ), FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(TextView(this@MainActivity).apply {
                    text = formatMinutes(this@MainActivity, (usedMs / 60_000L).toInt())
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Look.text(this@MainActivity))
                    gravity = Gravity.CENTER
                })
                addView(mutedText(13f).apply {
                    text = if (limitMs > 0)
                        getString(R.string.today_of_limit, formatMinutes(this@MainActivity, (limitMs / 60_000L).toInt()))
                    else getString(R.string.today_total)
                    gravity = Gravity.CENTER
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))

        // Рядки по додатках.
        val night = prefs.nightWindow()
        val nightNow = night.coversNow()
        val list = card()
        for (app in appsWithRules()) {
            val rule = rules[app.pkg] ?: continue
            val u = usage[app.pkg] ?: AppUsage()
            val limit = rule.minutesFor(today)
            val maxOpens = rule.opens
            val (status, blocked) = when {
                rule.night && nightNow -> getString(R.string.today_night) to true
                maxOpens != null && u.opens >= maxOpens -> getString(R.string.today_opens_out) to true
                limit != null && u.timeMs >= limit * 60_000L -> getString(R.string.today_blocked) to true
                limit != null -> getString(
                    R.string.today_left,
                    formatMinutes(this, ((limit * 60_000L - u.timeMs) / 60_000L).toInt().coerceAtLeast(1))
                ) to false
                maxOpens != null -> getString(R.string.widget_opens_left, maxOpens - u.opens) to false
                else -> getString(R.string.today_no_daytime) to false
            }
            val fraction = when {
                blocked -> 1f
                limit != null -> (u.timeMs.toFloat() / (limit * 60_000L)).coerceIn(0f, 1f)
                maxOpens != null -> (u.opens.toFloat() / maxOpens).coerceIn(0f, 1f)
                else -> 0f
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(ImageView(this@MainActivity).apply { setImageDrawable(app.icon) },
                    LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(TextView(this@MainActivity).apply {
                            text = app.label
                            textSize = 15f
                            setSingleLine(true)
                            setTextColor(Look.text(this@MainActivity))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(TextView(this@MainActivity).apply {
                            text = status
                            textSize = 13f
                            setTextColor(if (blocked) Look.danger(this@MainActivity) else Look.muted(this@MainActivity))
                        })
                    })
                    addView(progressBar(fraction, blocked), LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
                    ).apply { topMargin = dp(6) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { showLimitDialog(app) }
            })
        }
        page.addView(list, blockParams())

        if (rules.values.any { it.night }) {
            page.addView(mutedText(13f).apply {
                text = if (nightNow) getString(R.string.today_night_now, formatTime(night.end))
                else getString(R.string.today_night_info, formatTime(night.start), formatTime(night.end))
                setPadding(dp(4), dp(14), 0, 0)
            })
        }
        return page
    }

    private fun emptyState() = card().apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(28), dp(20), dp(28))
        addView(TextView(this@MainActivity).apply {
            text = getString(R.string.today_empty_title)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text(this@MainActivity))
            gravity = Gravity.CENTER
        })
        addView(mutedText(14f).apply {
            text = getString(R.string.today_empty_text)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        })
        addView(primaryButton(getString(R.string.btn_pick_apps)) { openPicker() })
    }

    private fun progressBar(fraction: Float, blocked: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Look.rounded(Look.barEmpty(this@MainActivity), dpf(3))
        val color = if (blocked) Look.danger(this@MainActivity) else Look.ACCENT_FILL
        if (fraction > 0f) {
            addView(View(this@MainActivity).apply { background = Look.rounded(color, dpf(3)) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraction))
        }
        if (fraction < 1f) {
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fraction))
        }
    }

    // =====================================================================
    // Вкладка «Ліміти»
    // =====================================================================

    private fun limitsPage(): View {
        val page = pageLayout(getString(R.string.tab_limits))
        val rules = prefs.rules()
        val pending = prefs.pendingRules()
        val apps = appsWithRules()

        if (apps.isNotEmpty()) {
            val list = card().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
            apps.forEachIndexed { i, app ->
                if (i > 0) list.addView(dividerLine())
                val rule = rules[app.pkg]
                val sb = StringBuilder(rule?.let { describe(it) } ?: getString(R.string.desc_none))
                pending[app.pkg]?.let { sb.append("\n").append(getString(R.string.row_tomorrow, describe(it))) }
                list.addView(listRow(app, sb.toString()) { showLimitDialog(app) })
            }
            page.addView(list, blockParams())
        }

        page.addView(TextView(this).apply {
            text = getString(R.string.btn_pick_apps)
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Look.accentText(this@MainActivity))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dpf(16)
                setStroke(dp(1), Look.chip(this@MainActivity), dpf(6), dpf(4))
            }
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { openPicker() }
        }, blockParams())
        page.addView(mutedText(13f).apply {
            text = getString(R.string.apps_hint)
            setPadding(dp(4), dp(8), dp(4), 0)
        })

        // Нічний режим
        page.addView(groupTitle(getString(R.string.section_night)))
        val w = prefs.nightWindow()
        val nightCount = rules.values.count { it.night }
        page.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill(getString(R.string.night_from_btn, formatTime(w.start))) { pickNightEdge(true) })
                addView(pill(getString(R.string.night_to_btn, formatTime(w.end))) { pickNightEdge(false) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(8) })
            })
            addView(mutedText(13f).apply {
                text = buildString {
                    append(
                        if (nightCount == 0) getString(R.string.night_apps_none)
                        else getString(R.string.night_apps_count, nightCount)
                    )
                    prefs.pendingNightWindow()?.let {
                        append("\n").append(getString(R.string.night_pending, formatTime(it.start), formatTime(it.end)))
                    }
                    append("\n").append(getString(R.string.night_hint))
                }
                setPadding(0, dp(10), 0, 0)
            })
        }, blockParams())
        return page
    }

    private fun listRow(app: AppItem, subtitle: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        addView(ImageView(this@MainActivity).apply { setImageDrawable(app.icon) },
            LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = app.label
                textSize = 15f
                setTextColor(Look.text(this@MainActivity))
            })
            addView(mutedText(13f).apply { text = subtitle })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chevron())
        setOnClickListener { onClick() }
    }

    // =====================================================================
    // Вкладка «Налаштування»
    // =====================================================================

    private fun settingsPage(): View {
        val page = pageLayout(getString(R.string.tab_settings))

        // Вигляд
        page.addView(groupTitle(getString(R.string.section_look)))
        page.addView(card().apply {
            addView(settingRow(getString(R.string.label_language),
                segmented(listOf(Lang.UK to "UA", Lang.EN to "EN"), Lang.get(this@MainActivity)) {
                    Lang.set(this@MainActivity, it)
                    recreate()
                }))
            addView(dividerLine())
            addView(settingRow(getString(R.string.label_theme),
                segmented(
                    listOf(
                        Look.AUTO to getString(R.string.theme_auto),
                        Look.LIGHT to getString(R.string.theme_light),
                        Look.DARK to getString(R.string.theme_dark)
                    ),
                    Look.get(this@MainActivity)
                ) {
                    Look.set(this@MainActivity, it)
                    recreate()
                }))
        }, blockParams())

        // Дозволи
        page.addView(groupTitle(getString(R.string.section_permissions)))
        page.addView(card().apply {
            val pm = getSystemService(PowerManager::class.java)
            class PermRow(val ok: Boolean, val label: Int, val button: Int, val critical: Boolean, val action: () -> Unit)
            val rows = listOf(
                PermRow(hasUsageAccess(), R.string.perm_usage, R.string.btn_grant, true) { openUsageAccess() },
                PermRow(isAccessibilityEnabled(), R.string.perm_a11y, R.string.btn_enable, true) { showAccessibilityHelp() },
                PermRow(notificationsEnabled(), R.string.perm_notifications, R.string.btn_allow, false) { requestNotifications() },
                PermRow(pm?.isIgnoringBatteryOptimizations(packageName) == true, R.string.perm_battery, R.string.btn_disable, false) {
                    openBatterySettings()
                }
            )
            rows.forEachIndexed { i, r ->
                if (i > 0) addView(dividerLine())
                val color = when {
                    r.ok -> Look.ok(this@MainActivity)
                    r.critical -> Look.danger(this@MainActivity)
                    else -> Look.warn(this@MainActivity)
                }
                val label = TextView(this@MainActivity).apply {
                    text = statusLine(color, getString(r.label))
                    textSize = 14f
                    setTextColor(Look.text(this@MainActivity))
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                    addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (!r.ok) addView(smallButton(getString(r.button)) { r.action() })
                })
            }
        }, blockParams())

        // Захист
        page.addView(groupTitle(getString(R.string.group_protection)))
        page.addView(card().apply {
            addView(Switch(this@MainActivity).apply {
                text = getString(R.string.protection_switch)
                textSize = 15f
                setTextColor(Look.text(this@MainActivity))
                isChecked = prefs.protectionEnabled() && prefs.protectionOffAt() == null
                setOnCheckedChangeListener { _, checked -> onProtectionToggled(checked) }
            })
            val offAt = prefs.protectionOffAt()
            addView(mutedText(13f).apply {
                text = when {
                    offAt != null -> getString(R.string.protection_note_pending, formatDateTime(offAt))
                    prefs.protectionEnabled() -> getString(R.string.protection_note_on)
                    else -> getString(R.string.protection_note_off)
                }
                setPadding(0, dp(8), 0, 0)
            })
        }, blockParams())

        // Сповіщення
        page.addView(groupTitle(getString(R.string.group_notifications)))
        page.addView(card().apply {
            addView(Switch(this@MainActivity).apply {
                text = getString(R.string.summary_switch)
                textSize = 15f
                setTextColor(Look.text(this@MainActivity))
                isChecked = prefs.summaryEnabled()
                setOnCheckedChangeListener { _, on ->
                    prefs.setSummary(on)
                    render()
                }
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                addView(pill(getString(R.string.summary_at, formatTime(prefs.summaryTime()))) { pickSummaryTime() }
                    .apply { alpha = if (prefs.summaryEnabled()) 1f else 0.4f })
            })
            addView(mutedText(13f).apply {
                text = getString(R.string.summary_hint)
                setPadding(0, dp(8), 0, 0)
            })
        }, blockParams())

        // Оновлення
        page.addView(groupTitle(getString(R.string.update_title)))
        page.addView(updatesCard(), blockParams())

        // Статистика і «Про додаток»
        page.addView(groupTitle(getString(R.string.group_about)))
        page.addView(card().apply {
            setPadding(dp(16), dp(4), dp(16), dp(4))
            addView(linkRow(getString(R.string.btn_weekly_stats)) {
                startActivity(Intent(this@MainActivity, StatsActivity::class.java))
            })
            addView(dividerLine())
            addView(linkRow(getString(R.string.about_source)) {
                safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.SOURCE_URL)))
            })
            addView(dividerLine())
            addView(mutedText(13f).apply {
                text = getString(R.string.about_license)
                setPadding(0, dp(12), 0, dp(12))
            })
        }, blockParams())

        // Автоперевірка (раз на день, лише за згоди) — після побудови сторінки.
        if (Updater.shouldAutoCheck(this) && updateState == null) ui.post { checkForUpdates() }
        return page
    }

    private fun linkRow(label: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, dp(12))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Look.text(this@MainActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(chevron())
        setOnClickListener { onClick() }
    }

    // =====================================================================
    // Оновлення
    // =====================================================================

    private fun updatesCard() = card().apply {
        val consent = Updater.consent(this@MainActivity)
        val allowed = consent == Updater.Consent.YES

        addView(Switch(this@MainActivity).apply {
            text = getString(R.string.upd_switch)
            textSize = 15f
            setTextColor(Look.text(this@MainActivity))
            isChecked = allowed
            setOnCheckedChangeListener { _, on ->
                if (on) askUpdateConsent() else {
                    Updater.setConsent(this@MainActivity, Updater.Consent.NO)
                    updateState = null
                    render()
                }
            }
        })
        addView(mutedText(13f).apply {
            text = getString(if (allowed) R.string.upd_explain else R.string.upd_off_hint)
            setPadding(0, dp(8), 0, 0)
        })
        addView(mutedText(13f).apply {
            text = getString(R.string.upd_installed, Updater.installedVersion(this@MainActivity))
            setPadding(0, dp(10), 0, 0)
        })

        if (allowed) {
            val release = updateRelease
            updateState?.let { state ->
                addView(TextView(this@MainActivity).apply {
                    text = state
                    textSize = 14f
                    setTextColor(Look.text(this@MainActivity))
                    setPadding(0, dp(6), 0, 0)
                })
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                if (release != null && Updater.isNewer(release.version, Updater.installedVersion(this@MainActivity))) {
                    addView(primaryButton(getString(R.string.upd_update)) { startUpdate(release) })
                } else {
                    addView(smallButton(getString(R.string.upd_check_now)) { checkForUpdates() })
                }
            })
        } else {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
                addView(smallButton(getString(R.string.upd_check_now)) { askUpdateConsent() })
            })
        }
    }

    /** Чесне пояснення, навіщо інтернет, і вибір «так / ні». */
    private fun askUpdateConsent() {
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setPositiveButton(R.string.consent_yes) { _, _ ->
                Updater.setConsent(this, Updater.Consent.YES)
                checkForUpdates()
            }
            .setNegativeButton(R.string.consent_no) { _, _ ->
                Updater.setConsent(this, Updater.Consent.NO)
                render()
            }
            .setOnCancelListener { render() }
            .show()
    }

    private fun checkForUpdates() {
        if (Updater.consent(this) != Updater.Consent.YES) return
        updateState = getString(R.string.upd_checking)
        updateRelease = null
        if (tab == TAB_SETTINGS) render()
        Thread {
            val result = runCatching { Updater.fetchLatest(applicationContext) }
            ui.post {
                val release = result.getOrNull()
                updateRelease = release
                updateState = when {
                    release == null -> getString(R.string.upd_error)
                    Updater.isNewer(release.version, Updater.installedVersion(this)) ->
                        getString(R.string.upd_available, release.version)
                    else -> getString(R.string.upd_latest)
                }
                if (tab == TAB_SETTINGS && !isFinishing) render()
            }
        }.start()
    }

    private fun startUpdate(release: Updater.Release) {
        if (!Updater.canInstall(this)) {
            explainThen(R.string.install_perm_title, R.string.install_perm_message) {
                safeStart(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            return
        }
        if (release.apkUrl == null) {
            safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
            return
        }
        val id = try {
            Updater.download(applicationContext, release)
        } catch (e: Exception) {
            updateState = getString(R.string.upd_download_error)
            render()
            return
        }
        updateState = getString(R.string.upd_downloading)
        render()
        val app = applicationContext
        Thread {
            var done: Boolean? = null
            for (i in 0 until 600) { // до 10 хвилин
                done = Updater.downloadStatus(app, id)
                if (done != null) break
                Thread.sleep(1000)
            }
            ui.post {
                if (done == true && Updater.install(app, id)) {
                    updateState = null
                } else {
                    updateState = getString(R.string.upd_download_error)
                }
                if (!isFinishing && tab == TAB_SETTINGS) render()
            }
        }.start()
    }

    // =====================================================================
    // Дозволи
    // =====================================================================

    private fun allPermissionsOk(): Boolean = hasUsageAccess() && isAccessibilityEnabled()

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val mine = ComponentName(this, LimitService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /**
     * Системні сторінки Android показує мовою телефона, а не мовою LockTime.
     * Тому перед ними — коротке пояснення обраною мовою, що саме натиснути.
     */
    private fun explainThen(titleRes: Int, messageRes: Int, open: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.btn_continue) { _, _ -> open() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun openUsageAccess() {
        explainThen(R.string.usage_title, R.string.usage_message) {
            safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun showAccessibilityHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.a11y_title)
            .setMessage(R.string.a11y_message)
            .setPositiveButton(R.string.btn_open) { _, _ ->
                safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton(R.string.btn_app_info) { _, _ ->
                safeStart(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        safeStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) {
            // Якщо системне вікно більше не показується (відмовили двічі) — відкриваємо налаштування.
            if (!notificationsEnabled()) openNotificationSettings()
            render()
        }
    }

    private fun openBatterySettings() {
        explainThen(R.string.battery_title, R.string.battery_message) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            if (!safeStart(direct)) {
                safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun onProtectionToggled(checked: Boolean) {
        if (checked) {
            AlertDialog.Builder(this)
                .setTitle(R.string.protect_on_title)
                .setMessage(R.string.protect_on_message)
                .setPositiveButton(R.string.btn_turn_on) { _, _ ->
                    prefs.setProtection(true)
                    render()
                }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> render() }
                .setOnCancelListener { render() }
                .show()
        } else {
            val offAt = prefs.setProtection(false)
            render()
            if (offAt != null) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.protect_off_title)
                    .setMessage(getString(R.string.protect_off_message, formatDateTime(offAt)))
                    .setPositiveButton(R.string.btn_got_it, null)
                    .show()
            }
        }
    }

    private fun safeStart(intent: Intent): Boolean =
        try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }

    // =====================================================================
    // Час: підсумок і нічний режим
    // =====================================================================

    private fun pickSummaryTime() {
        val t = prefs.summaryTime()
        TimePickerDialog(this, { _, h, m ->
            prefs.setSummary(true, h * 60 + m)
            render()
        }, t / 60, t % 60, true).show()
    }

    private fun pickNightEdge(isStart: Boolean) {
        val current = prefs.pendingNightWindow() ?: prefs.nightWindow()
        val minute = if (isStart) current.start else current.end
        TimePickerDialog(this, { _, h, m ->
            val value = h * 60 + m
            val window = if (isStart) NightWindow(value, current.end) else NightWindow(current.start, value)
            if (window.start == window.end) {
                Toast.makeText(this, getString(R.string.toast_night_same), Toast.LENGTH_LONG).show()
            } else {
                showResult(prefs.setNightWindow(window))
                render()
                LimitWidget.update(this)
            }
        }, minute / 60, minute % 60, true).apply {
            setTitle(getString(if (isStart) R.string.night_from_title else R.string.night_to_title))
        }.show()
    }

    // =====================================================================
    // Додатки і правила
    // =====================================================================

    /** Додатки з чинними або запланованими правилами, за абеткою. */
    private fun appsWithRules(): List<AppItem> {
        val limited = prefs.rules().keys + prefs.pendingRules().keys
        return allApps.filter { it.pkg in limited }
    }

    private fun openPicker() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, AppPickerActivity::class.java), REQ_PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK) return
        val pkg = data?.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
        val app = allApps.firstOrNull { it.pkg == pkg } ?: return
        showLimitDialog(app)
    }

    private fun numberField(value: Int?, hintText: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = hintText
        setText(value?.toString() ?: "")
        setSelectAllOnFocus(true)
    }

    /** Ряд «чипів» швидкого вибору; чип, що збігається з полем, підсвічується. */
    private fun chipRow(target: EditText, values: List<Int>, labelOf: (Int) -> String): View {
        val chips = mutableListOf<Pair<Int, TextView>>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in values) {
            val chip = TextView(this).apply {
                text = labelOf(v)
                textSize = 13f
                setSingleLine(true)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener {
                    target.setText(v.toString())
                    target.setSelection(target.text.length)
                }
            }
            row.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
            chips.add(v to chip)
        }
        fun refresh() {
            val current = target.text.toString().toIntOrNull()
            for ((value, chip) in chips) {
                val selected = value == current
                chip.background = Look.rounded(if (selected) Look.ACCENT_FILL else Look.chip(this), dpf(16))
                chip.setTextColor(if (selected) Look.WHITE else Look.text(this))
            }
        }
        target.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, dp(2), 0, dp(4))
            addView(row)
        }
    }

    private fun dialogSection(title: String) = TextView(this).apply {
        text = title
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Look.text(this@MainActivity))
        setPadding(0, dp(16), 0, dp(2))
    }

    private fun fieldLabel(text: String) = mutedText(13f).apply {
        this.text = text
        setPadding(0, dp(8), 0, 0)
    }

    private fun showLimitDialog(app: AppItem) {
        val current = prefs.rules()[app.pkg]
        val shown = prefs.pendingRules()[app.pkg] ?: current ?: Rule()
        val minutesLabel: (Int) -> String = { formatMinutes(this, it) }
        fun parseMinutes(e: EditText) =
            e.text.toString().toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(Prefs.MAX_MINUTES)

        // Будні / вихідні
        val m = shown.minutes
        val weekdayInput = numberField(m[0], getString(R.string.hint_type_number))
        val weekendInput = numberField(m[5].takeIf { it != m[0] }, getString(R.string.hint_same_as_weekdays))
        val weekBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fieldLabel(getString(R.string.dlg_weekdays)))
            addView(weekdayInput)
            addView(chipRow(weekdayInput, PRESET_MINUTES, minutesLabel))
            addView(fieldLabel(getString(R.string.dlg_weekends)))
            addView(weekendInput)
            addView(chipRow(weekendInput, PRESET_MINUTES, minutesLabel))
        }

        // Окремо по днях тижня
        val dayNames = getString(R.string.days_short).split(",")
        val dayInputs = List(7) { numberField(m[it], getString(R.string.hint_no_limit)) }
        val daysBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (i in 0 until 7) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = dayNames.getOrElse(i) { "" }
                        textSize = 14f
                        setTextColor(Look.text(this@MainActivity))
                    }, LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(dayInputs[i], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(mutedText(13f).apply { text = getString(R.string.dlg_minutes_hint) })
                })
            }
        }

        var perDay = !shown.isWeekPattern
        val modeToggle = TextView(this).apply {
            textSize = 14f
            setTextColor(Look.accentText(this@MainActivity))
            setPadding(0, dp(10), 0, dp(4))
        }
        fun applyMode() {
            weekBlock.visibility = if (perDay) View.GONE else View.VISIBLE
            daysBlock.visibility = if (perDay) View.VISIBLE else View.GONE
            modeToggle.text = getString(if (perDay) R.string.dlg_same_week else R.string.dlg_per_day)
        }
        modeToggle.setOnClickListener {
            if (!perDay) {
                val wd = parseMinutes(weekdayInput)
                val we = parseMinutes(weekendInput) ?: wd
                for (i in 0 until 7) dayInputs[i].setText((if (i < 5) wd else we)?.toString() ?: "")
            } else {
                val mon = parseMinutes(dayInputs[0])
                val sat = parseMinutes(dayInputs[5])
                weekdayInput.setText(mon?.toString() ?: "")
                weekendInput.setText(if (sat == mon) "" else sat?.toString() ?: "")
            }
            perDay = !perDay
            applyMode()
        }
        applyMode()

        val opensInput = numberField(shown.opens, getString(R.string.hint_type_number))
        val nightBox = CheckBox(this).apply {
            val w = prefs.nightWindow()
            text = getString(R.string.night_checkbox, formatTime(w.start), formatTime(w.end))
            isChecked = shown.night
        }

        var dialog: AlertDialog? = null
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), dp(8))

            addView(dialogSection(getString(R.string.dlg_time)))
            addView(mutedText(12f).apply { text = getString(R.string.dlg_time_hint) })
            addView(weekBlock)
            addView(daysBlock)
            addView(modeToggle)

            addView(dialogSection(getString(R.string.dlg_opens)))
            addView(mutedText(12f).apply { text = getString(R.string.dlg_opens_hint) })
            addView(opensInput)
            addView(chipRow(opensInput, PRESET_OPENS) { it.toString() })

            addView(dialogSection(getString(R.string.section_night)))
            addView(nightBox)

            addView(mutedText(12f).apply {
                text = getString(R.string.rule_footer)
                setPadding(0, dp(14), 0, 0)
            })

            if (current != null) {
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.btn_remove_limits)
                    textSize = 14f
                    setTextColor(Look.danger(this@MainActivity))
                    setPadding(0, dp(14), 0, dp(4))
                    setOnClickListener {
                        applyRule(app.pkg, Rule())
                        dialog?.dismiss()
                    }
                })
            }
        }

        val created = AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val opens = opensInput.text.toString().toIntOrNull()?.takeIf { it > 0 }
                val rule = if (perDay) {
                    Rule(List(7) { parseMinutes(dayInputs[it]) }, opens, nightBox.isChecked).normalized()
                } else {
                    Rule.of(parseMinutes(weekdayInput), parseMinutes(weekendInput), opens, nightBox.isChecked)
                }
                applyRule(app.pkg, rule)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog = created
        created.show()
    }

    private fun applyRule(pkg: String, rule: Rule) {
        showResult(prefs.setRule(pkg, rule))
        LimitWidget.update(this)
        if (!allPermissionsOk()) {
            Toast.makeText(this, getString(R.string.toast_need_permissions), Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun showResult(result: Prefs.Result) {
        val msg = when (result) {
            Prefs.Result.APPLIED -> getString(R.string.result_applied)
            Prefs.Result.PARTLY -> getString(R.string.result_partly)
            Prefs.Result.TOMORROW -> getString(R.string.result_tomorrow)
            Prefs.Result.UNCHANGED -> return
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun minutesOrNone(v: Int?): String =
        v?.let { formatMinutes(this, it) } ?: getString(R.string.desc_unlimited)

    private fun describe(rule: Rule): String {
        val parts = mutableListOf<String>()
        val m = rule.minutes
        val first = m[0]
        when {
            m.all { it == null } -> {}
            first != null && m.all { it == first } -> parts.add(getString(R.string.desc_per_day, formatMinutes(this, first)))
            rule.isWeekPattern -> {
                parts.add(getString(R.string.desc_weekdays, minutesOrNone(m[0])))
                parts.add(getString(R.string.desc_weekends, minutesOrNone(m[5])))
            }
            else -> parts.add(getString(R.string.desc_by_day, minutesOrNone(rule.minutesFor(LocalDate.now()))))
        }
        rule.opens?.let { parts.add(getString(R.string.desc_opens, it)) }
        if (rule.night) parts.add(getString(R.string.desc_night))
        return if (parts.isEmpty()) getString(R.string.desc_none) else parts.joinToString(" · ")
    }

    // =====================================================================
    // Будівельні блоки інтерфейсу
    // =====================================================================

    /** Сторінка з великим заголовком. */
    private fun pageLayout(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(24))
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text(this@MainActivity))
            setPadding(dp(4), dp(8), 0, dp(8))
        })
    }

    private fun groupTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Look.muted(this@MainActivity))
        setPadding(dp(4), dp(20), 0, dp(2))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.rounded(Look.card(this@MainActivity), dpf(16))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun blockParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun dividerLine() = View(this).apply {
        setBackgroundColor(Look.divider(this@MainActivity))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun chevron() = TextView(this).apply {
        text = "›"
        textSize = 22f
        setTextColor(Look.muted(this@MainActivity))
        setPadding(dp(8), 0, 0, 0)
    }

    private fun mutedText(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Look.muted(this@MainActivity))
    }

    private fun settingRow(label: String, control: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(8), 0, dp(8))
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Look.text(this@MainActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(control)
    }

    private fun segmented(
        options: List<Pair<String, String>>,
        selected: String,
        onPick: (String) -> Unit
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Look.rounded(Look.chip(this@MainActivity), dpf(18))
        setPadding(dp(3), dp(3), dp(3), dp(3))
        for ((code, label) in options) {
            val on = code == selected
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setSingleLine(true)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(if (on) Look.WHITE else Look.text(this@MainActivity))
                if (on) background = Look.rounded(Look.ACCENT_FILL, dpf(15))
                setOnClickListener { if (!on) onPick(code) }
            })
        }
    }

    /** Велика «пігулка» (час нічного режиму, підсумку). */
    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Look.accentText(this@MainActivity))
        background = Look.rounded(Look.chip(this@MainActivity), dpf(18))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    private fun smallButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setSingleLine(true)
        setTextColor(Look.accentText(this@MainActivity))
        background = Look.rounded(Look.chip(this@MainActivity), dpf(16))
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Look.WHITE)
        background = Look.rounded(Look.ACCENT_FILL, dpf(20))
        setPadding(dp(24), dp(10), dp(24), dp(10))
        setOnClickListener { onClick() }
    }

    /** Рядок стану: маленька кольорова крапка і підпис. */
    private fun statusLine(color: Int, label: String): CharSequence =
        SpannableStringBuilder("●  $label").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    companion object {
        private const val KEY_TAB = "tab"
        private const val TAB_TODAY = 0
        private const val TAB_LIMITS = 1
        private const val TAB_SETTINGS = 2
        private const val REQ_NOTIFICATIONS = 1
        private const val REQ_PICK = 2
        private val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60)
        private val PRESET_OPENS = listOf(3, 5, 10, 20)

        fun formatTime(minuteOfDay: Int): String =
            String.format(java.util.Locale.ROOT, "%02d:%02d", minuteOfDay / 60, minuteOfDay % 60)

        fun formatDateTime(millis: Long): String =
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(java.util.Date(millis))

        fun formatMinutes(context: Context, minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h == 0 -> context.getString(R.string.fmt_minutes, m)
                m == 0 -> context.getString(R.string.fmt_hours, h)
                else -> context.getString(R.string.fmt_hours_minutes, h, m)
            }
        }
    }
}

/** Кільце прогресу для вкладки «Сьогодні». */
private class RingView(
    context: Context,
    private val fraction: Float,
    private val color: Int,
    private val track: Int
) : View(context) {
    private val stroke = 12 * resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val half = stroke / 2
        box.set(half, half, width - half, height - half)
        paint.color = track
        canvas.drawArc(box, 0f, 360f, false, paint)
        if (fraction > 0f) {
            paint.color = color
            canvas.drawArc(box, -90f, 360f * fraction, false, paint)
        }
    }
}
