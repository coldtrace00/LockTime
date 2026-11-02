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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

class MainActivity : Activity() {

    data class AppItem(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var prefs: Prefs
    private lateinit var listView: ListView
    private lateinit var adapter: AppAdapter

    private lateinit var permRows: LinearLayout
    private lateinit var permAllOk: TextView
    private lateinit var usageStatus: TextView
    private lateinit var usageButton: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var a11yButton: TextView
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: TextView
    private lateinit var protectionSwitch: Switch
    private lateinit var protectionNote: TextView
    private lateinit var loadingText: TextView
    private lateinit var nightFromButton: TextView
    private lateinit var nightToButton: TextView
    private lateinit var nightText: TextView

    private var apps: List<AppItem> = emptyList()
    private var usage: Map<String, AppUsage> = emptyMap()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Look.themeRes(this))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }

        // ---- Вигляд: мова і тема ----
        header.addView(card().apply {
            addView(settingRow(
                getString(R.string.label_language),
                segmented(listOf(Lang.UK to "UA", Lang.EN to "EN"), Lang.get(this@MainActivity)) {
                    Lang.set(this@MainActivity, it)
                    recreate()
                }
            ))
            addView(settingRow(
                getString(R.string.label_theme),
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
                }
            ).apply { setPadding(0, dp(10), 0, 0) })
        }, cardParams())

        // ---- Дозволи ----
        usageStatus = TextView(this)
        usageButton = smallButton(getString(R.string.btn_grant)) { openUsageAccess() }
        a11yStatus = TextView(this)
        a11yButton = smallButton(getString(R.string.btn_enable)) { showAccessibilityHelp() }
        notifStatus = TextView(this)
        notifButton = smallButton(getString(R.string.btn_allow)) { requestNotifications() }
        batteryStatus = TextView(this)
        batteryButton = smallButton(getString(R.string.btn_disable)) { openBatterySettings() }

        permAllOk = TextView(this).apply {
            text = statusLine(Look.ok(this@MainActivity), getString(R.string.perm_all_ok))
            textSize = 15f
            setTextColor(Look.text(this@MainActivity))
        }
        permRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle(getString(R.string.section_permissions)))
            addView(permissionRow(usageStatus, usageButton))
            addView(permissionRow(a11yStatus, a11yButton))
            addView(permissionRow(notifStatus, notifButton))
            addView(permissionRow(batteryStatus, batteryButton))
        }
        header.addView(card().apply {
            addView(permAllOk)
            addView(permRows)
        }, cardParams())

        // ---- Захист від видалення ----
        protectionSwitch = Switch(this).apply {
            text = getString(R.string.protection_switch)
            textSize = 15f
            setTextColor(Look.text(this@MainActivity))
        }
        protectionNote = mutedText(13f).apply { setPadding(0, dp(6), 0, 0) }
        header.addView(card().apply {
            addView(cardTitle(getString(R.string.section_protection)))
            addView(protectionSwitch)
            addView(protectionNote)
        }, cardParams())

        // ---- Нічний режим ----
        nightFromButton = pill { pickNightEdge(isStart = true) }
        nightToButton = pill { pickNightEdge(isStart = false) }
        nightText = mutedText(13f).apply { setPadding(0, dp(8), 0, 0) }
        header.addView(card().apply {
            addView(cardTitle(getString(R.string.section_night)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(nightFromButton)
                addView(nightToButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) })
            })
            addView(nightText)
        }, cardParams())

        // ---- Статистика ----
        header.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.btn_weekly_stats)
                    textSize = 15f
                    setTextColor(Look.text(this@MainActivity))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 22f
                    setTextColor(Look.muted(this@MainActivity))
                })
            })
            isClickable = true
            setOnClickListener { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        }, cardParams())

        // ---- Додатки ----
        header.addView(TextView(this).apply {
            text = getString(R.string.section_apps)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text(this@MainActivity))
            setPadding(0, dp(20), 0, dp(4))
        })
        header.addView(mutedText(13f).apply { text = getString(R.string.apps_hint) })
        loadingText = TextView(this).apply {
            text = getString(R.string.loading_apps)
            setPadding(0, dp(12), 0, 0)
        }
        header.addView(loadingText)

        adapter = AppAdapter()
        listView = ListView(this).apply {
            addHeaderView(header, null, false)
            adapter = this@MainActivity.adapter
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                val index = position - headerViewsCount
                if (index in apps.indices) showLimitDialog(apps[index])
            }
        }
        setContentView(listView)

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshUsage()
        sortApps()
        adapter.notifyDataSetChanged()
    }

    // ---------- Стан ----------

    private fun refreshStatus() {
        val hasUsage = hasUsageAccess()
        usageStatus.text = statusLine(if (hasUsage) Look.ok(this) else Look.danger(this), getString(R.string.perm_usage))
        usageButton.visibility = if (hasUsage) View.GONE else View.VISIBLE

        val hasA11y = isAccessibilityEnabled()
        a11yStatus.text = statusLine(if (hasA11y) Look.ok(this) else Look.danger(this), getString(R.string.perm_a11y))
        a11yButton.visibility = if (hasA11y) View.GONE else View.VISIBLE

        val notif = notificationsEnabled()
        notifStatus.text = statusLine(if (notif) Look.ok(this) else Look.warn(this), getString(R.string.perm_notifications))
        notifButton.visibility = if (notif) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val noBatteryLimit = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatus.text = statusLine(if (noBatteryLimit) Look.ok(this) else Look.warn(this), getString(R.string.perm_battery))
        batteryButton.visibility = if (noBatteryLimit) View.GONE else View.VISIBLE

        // Коли все надано — показуємо один рядок замість чотирьох.
        val allOk = hasUsage && hasA11y && notif && noBatteryLimit
        permAllOk.visibility = if (allOk) View.VISIBLE else View.GONE
        permRows.visibility = if (allOk) View.GONE else View.VISIBLE

        protectionSwitch.setOnCheckedChangeListener(null)
        protectionSwitch.isChecked = prefs.protectionEnabled() && prefs.protectionOffAt() == null
        protectionSwitch.setOnCheckedChangeListener { _, checked -> onProtectionToggled(checked) }
        val offAt = prefs.protectionOffAt()
        protectionNote.text = when {
            offAt != null -> getString(R.string.protection_note_pending, formatDateTime(offAt))
            prefs.protectionEnabled() -> getString(R.string.protection_note_on)
            else -> getString(R.string.protection_note_off)
        }

        val w = prefs.nightWindow()
        nightFromButton.text = getString(R.string.night_from_btn, formatTime(w.start))
        nightToButton.text = getString(R.string.night_to_btn, formatTime(w.end))
        val nightApps = prefs.rules().values.count { it.night }
        val pendingNight = prefs.pendingNightWindow()
        nightText.text = buildString {
            append(
                if (nightApps == 0) getString(R.string.night_apps_none)
                else getString(R.string.night_apps_count, nightApps)
            )
            if (w.coversNow() && nightApps > 0) append("\n").append(getString(R.string.night_active_now))
            if (pendingNight != null) {
                append("\n").append(
                    getString(R.string.night_pending, formatTime(pendingNight.start), formatTime(pendingNight.end))
                )
            }
            append("\n").append(getString(R.string.night_hint))
        }
    }

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

    private fun openUsageAccess() {
        safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        Toast.makeText(this, getString(R.string.toast_find_in_list), Toast.LENGTH_LONG).show()
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

    private fun notificationsEnabled(): Boolean =
        getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

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
            refreshStatus()
        }
    }

    private fun openBatterySettings() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (!safeStart(direct)) {
            safeStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun onProtectionToggled(checked: Boolean) {
        if (checked) {
            AlertDialog.Builder(this)
                .setTitle(R.string.protect_on_title)
                .setMessage(R.string.protect_on_message)
                .setPositiveButton(R.string.btn_turn_on) { _, _ ->
                    prefs.setProtection(true)
                    refreshStatus()
                }
                .setNegativeButton(R.string.btn_cancel) { _, _ -> refreshStatus() }
                .setOnCancelListener { refreshStatus() }
                .show()
        } else {
            val offAt = prefs.setProtection(false)
            refreshStatus()
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

    // ---------- Нічний режим ----------

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
                refreshStatus()
            }
        }, minute / 60, minute % 60, true).apply {
            setTitle(getString(if (isStart) R.string.night_from_title else R.string.night_to_title))
        }.show()
    }

    // ---------- Список додатків ----------

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val packages = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
            val items = packages.mapNotNull { pkg ->
                try {
                    @Suppress("DEPRECATION")
                    val info = pm.getApplicationInfo(pkg, 0)
                    AppItem(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
                } catch (e: Exception) {
                    null
                }
            }
            runOnUiThread {
                apps = items
                loadingText.visibility = View.GONE
                sortApps()
                refreshUsage()
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun sortApps() {
        val limited = prefs.rules().keys + prefs.pendingRules().keys
        apps = apps.sortedWith(
            compareBy<AppItem>({ if (it.pkg in limited) 0 else 1 }, { it.label.lowercase() })
        )
    }

    private fun refreshUsage() {
        usage = if (hasUsageAccess()) {
            UsageTracker.usageToday(this, prefs.rules().keys, null)
        } else {
            emptyMap()
        }
    }

    // ---------- Вікно правил додатка ----------

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
            for ((value, chip) in chips) styleChip(chip, value == current)
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

    private fun styleChip(chip: TextView, selected: Boolean) {
        chip.background = Look.rounded(if (selected) Look.ACCENT_FILL else Look.chip(this), dpf(16))
        chip.setTextColor(if (selected) Look.WHITE else Look.text(this))
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

        val weekdayInput = numberField(shown.weekday, getString(R.string.hint_type_number))
        val weekendInput = numberField(shown.weekend, getString(R.string.hint_same_as_weekdays))
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
            addView(fieldLabel(getString(R.string.dlg_weekdays)))
            addView(weekdayInput)
            addView(chipRow(weekdayInput, PRESET_MINUTES, minutesLabel))
            addView(fieldLabel(getString(R.string.dlg_weekends)))
            addView(weekendInput)
            addView(chipRow(weekendInput, PRESET_MINUTES, minutesLabel))

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
                fun parse(e: EditText) = e.text.toString().toIntOrNull()?.takeIf { it > 0 }
                val rule = Rule(
                    weekday = parse(weekdayInput)?.coerceAtMost(Prefs.MAX_MINUTES),
                    weekend = parse(weekendInput)?.coerceAtMost(Prefs.MAX_MINUTES),
                    opens = parse(opensInput),
                    night = nightBox.isChecked
                )
                applyRule(app.pkg, rule)
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .create()
        dialog = created
        created.show()
    }

    private fun applyRule(pkg: String, rule: Rule) {
        showResult(prefs.setRule(pkg, rule))
        if (!isAccessibilityEnabled() || !hasUsageAccess()) {
            Toast.makeText(this, getString(R.string.toast_need_permissions), Toast.LENGTH_LONG).show()
        }
        sortApps()
        refreshUsage()
        refreshStatus()
        adapter.notifyDataSetChanged()
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

    private fun describe(rule: Rule): String {
        val parts = mutableListOf<String>()
        val wd = rule.weekday
        val we = rule.weekend
        when {
            wd != null && we != null -> {
                parts.add(getString(R.string.desc_weekdays, formatMinutes(this, wd)))
                parts.add(getString(R.string.desc_weekends, formatMinutes(this, we)))
            }
            we != null -> parts.add(getString(R.string.desc_weekends, formatMinutes(this, we)))
            wd != null -> parts.add(getString(R.string.desc_per_day, formatMinutes(this, wd)))
        }
        rule.opens?.let { parts.add(getString(R.string.desc_opens, it)) }
        if (rule.night) parts.add(getString(R.string.desc_night))
        return if (parts.isEmpty()) getString(R.string.desc_none) else parts.joinToString(" · ")
    }

    // ---------- Рядок списку ----------

    private class RowHolder(val icon: ImageView, val name: TextView, val subtitle: TextView)

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row: View
            val holder: RowHolder
            if (convertView == null) {
                val icon = ImageView(this@MainActivity)
                val name = TextView(this@MainActivity).apply {
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Look.text(this@MainActivity))
                }
                val subtitle = TextView(this@MainActivity).apply { textSize = 13f }
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(name)
                    addView(subtitle)
                }
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(20), dp(10), dp(16), dp(10))
                    addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
                    addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "›"
                        textSize = 22f
                        setTextColor(Look.muted(this@MainActivity))
                        setPadding(dp(8), 0, 0, 0)
                    })
                }
                holder = RowHolder(icon, name, subtitle)
                row.tag = holder
            } else {
                row = convertView
                holder = convertView.tag as RowHolder
            }

            val app = apps[position]
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label

            val rule = prefs.rules()[app.pkg]
            val pending = prefs.pendingRules()[app.pkg]
            val used = usage[app.pkg] ?: AppUsage()

            var blocked = false
            val sb = StringBuilder()
            if (rule == null) {
                sb.append(getString(R.string.row_no_limit))
            } else {
                val limitMin = rule.minutesFor(LocalDate.now())
                val maxOpens = rule.opens
                val status = when {
                    rule.night && prefs.nightWindow().coversNow() -> getString(R.string.status_night)
                    maxOpens != null && used.opens >= maxOpens -> getString(R.string.status_opens)
                    limitMin != null && used.timeMs >= limitMin * 60_000L -> getString(R.string.status_time)
                    else -> null
                }
                if (status != null) {
                    blocked = true
                    sb.append(status).append("\n")
                }
                sb.append(describe(rule))
                sb.append("\n").append(
                    getString(
                        R.string.row_today,
                        formatMinutes(this@MainActivity, (used.timeMs / 60_000L).toInt()),
                        used.opens
                    )
                )
            }
            if (pending != null) {
                sb.append("\n").append(getString(R.string.row_tomorrow, describe(pending)))
            }
            holder.subtitle.text = sb.toString()
            holder.subtitle.setTextColor(
                when {
                    blocked -> Look.danger(this@MainActivity)
                    rule != null -> Look.accentText(this@MainActivity)
                    else -> Look.muted(this@MainActivity)
                }
            )
            return row
        }
    }

    // ---------- Будівельні блоки інтерфейсу ----------

    /** Секція-картка з заокругленими кутами. */
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.rounded(Look.card(this@MainActivity), dpf(16))
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(10) }

    private fun cardTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Look.text(this@MainActivity))
        setPadding(0, 0, 0, dp(8))
    }

    private fun mutedText(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Look.muted(this@MainActivity))
    }

    /** Підпис ліворуч, елемент керування праворуч. */
    private fun settingRow(label: String, control: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(this@MainActivity).apply {
            text = label
            textSize = 15f
            setTextColor(Look.text(this@MainActivity))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(control)
    }

    /** Сегментований перемикач: кілька варіантів, вибраний підсвічено. */
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

    /** Велика кнопка-«пігулка» з часом нічного режиму. */
    private fun pill(onClick: () -> Unit) = TextView(this).apply {
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Look.accentText(this@MainActivity))
        background = Look.rounded(Look.chip(this@MainActivity), dpf(18))
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener { onClick() }
    }

    /** Невелика кнопка-«пігулка» замість сірої системної. */
    private fun smallButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f
        setSingleLine(true)
        setTextColor(Look.accentText(this@MainActivity))
        background = Look.rounded(Look.chip(this@MainActivity), dpf(16))
        setPadding(dp(14), dp(6), dp(14), dp(6))
        setOnClickListener { onClick() }
    }

    /** Рядок стану: маленька кольорова крапка і підпис. */
    private fun statusLine(color: Int, label: String): CharSequence =
        SpannableStringBuilder("●  $label").apply {
            setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    private fun permissionRow(status: TextView, button: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
        status.textSize = 14f
        status.setTextColor(Look.text(this@MainActivity))
        addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(button)
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
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
