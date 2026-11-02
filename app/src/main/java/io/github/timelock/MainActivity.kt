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
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
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

    private lateinit var usageStatus: TextView
    private lateinit var usageButton: Button
    private lateinit var a11yStatus: TextView
    private lateinit var a11yButton: Button
    private lateinit var notifStatus: TextView
    private lateinit var notifButton: Button
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var protectionSwitch: Switch
    private lateinit var protectionNote: TextView
    private lateinit var loadingText: TextView
    private lateinit var nightText: TextView

    private var apps: List<AppItem> = emptyList()
    private var usage: Map<String, AppUsage> = emptyMap()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        // Мова: назва розділу двомовна, щоб її було легко знайти будь-якою мовою.
        header.addView(sectionTitle("Мова / Language"))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val current = Lang.get(this@MainActivity)
            for ((code, name) in listOf(Lang.UK to "Українська", Lang.EN to "English")) {
                addView(Button(this@MainActivity).apply {
                    text = if (code == current) "✓ $name" else name
                    setAllCaps(false)
                    setOnClickListener {
                        if (code != Lang.get(this@MainActivity)) {
                            Lang.set(this@MainActivity, code)
                            recreate()
                        }
                    }
                })
            }
        })

        header.addView(sectionTitle(getString(R.string.section_permissions)))

        usageStatus = TextView(this)
        usageButton = Button(this).apply {
            text = getString(R.string.btn_grant)
            setOnClickListener { openUsageAccess() }
        }
        header.addView(permissionRow(usageStatus, usageButton))

        a11yStatus = TextView(this)
        a11yButton = Button(this).apply {
            text = getString(R.string.btn_enable)
            setOnClickListener { showAccessibilityHelp() }
        }
        header.addView(permissionRow(a11yStatus, a11yButton))

        notifStatus = TextView(this)
        notifButton = Button(this).apply {
            text = getString(R.string.btn_allow)
            setOnClickListener { requestNotifications() }
        }
        header.addView(permissionRow(notifStatus, notifButton))

        batteryStatus = TextView(this)
        batteryButton = Button(this).apply {
            text = getString(R.string.btn_disable)
            setOnClickListener { openBatterySettings() }
        }
        header.addView(permissionRow(batteryStatus, batteryButton))

        header.addView(sectionTitle(getString(R.string.section_protection)))
        protectionSwitch = Switch(this).apply {
            text = getString(R.string.protection_switch)
            textSize = 16f
        }
        header.addView(protectionSwitch)
        protectionNote = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(protectionNote)

        header.addView(sectionTitle(getString(R.string.section_night)))
        nightText = TextView(this).apply { textSize = 14f }
        header.addView(nightText)
        header.addView(Button(this).apply {
            text = getString(R.string.btn_change_time)
            setOnClickListener { pickNightWindow() }
        })

        header.addView(sectionTitle(getString(R.string.section_stats)))
        header.addView(Button(this).apply {
            text = getString(R.string.btn_weekly_stats)
            setOnClickListener { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        })

        header.addView(sectionTitle(getString(R.string.section_apps)))
        header.addView(TextView(this).apply {
            text = getString(R.string.apps_hint)
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        loadingText = TextView(this).apply {
            text = getString(R.string.loading_apps)
            setPadding(0, dp(12), 0, 0)
        }
        header.addView(loadingText)

        adapter = AppAdapter()
        listView = ListView(this).apply {
            addHeaderView(header, null, false)
            adapter = this@MainActivity.adapter
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

    // ---------- Статус дозволів ----------

    private fun refreshStatus() {
        val hasUsage = hasUsageAccess()
        usageStatus.text = (if (hasUsage) "✅ " else "❌ ") + getString(R.string.perm_usage)
        usageButton.visibility = if (hasUsage) View.GONE else View.VISIBLE

        val hasA11y = isAccessibilityEnabled()
        a11yStatus.text = (if (hasA11y) "✅ " else "❌ ") + getString(R.string.perm_a11y)
        a11yButton.visibility = if (hasA11y) View.GONE else View.VISIBLE

        val notif = notificationsEnabled()
        notifStatus.text = (if (notif) "✅ " else "⚠️ ") + getString(R.string.perm_notifications)
        notifButton.visibility = if (notif) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val noBatteryLimit = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatus.text = (if (noBatteryLimit) "✅ " else "⚠️ ") + getString(R.string.perm_battery)
        batteryButton.visibility = if (noBatteryLimit) View.GONE else View.VISIBLE

        protectionSwitch.setOnCheckedChangeListener(null)
        protectionSwitch.isChecked = prefs.protectionEnabled() && prefs.protectionOffAt() == null
        protectionSwitch.setOnCheckedChangeListener { _, checked -> onProtectionToggled(checked) }
        val w = prefs.nightWindow()
        val nightApps = prefs.rules().values.count { it.night }
        val pendingNight = prefs.pendingNightWindow()
        nightText.text = buildString {
            if (nightApps == 0) {
                append(getString(R.string.night_summary_none, formatTime(w.start), formatTime(w.end)))
            } else {
                append(getString(R.string.night_summary_count, formatTime(w.start), formatTime(w.end), nightApps))
            }
            if (w.coversNow() && nightApps > 0) append("\n").append(getString(R.string.night_active_now))
            if (pendingNight != null) {
                append("\n").append(
                    getString(R.string.night_pending, formatTime(pendingNight.start), formatTime(pendingNight.end))
                )
            }
        }

        val offAt = prefs.protectionOffAt()
        protectionNote.text = when {
            offAt != null -> getString(R.string.protection_note_pending, formatDateTime(offAt))
            prefs.protectionEnabled() -> getString(R.string.protection_note_on)
            else -> getString(R.string.protection_note_off)
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

    // ---------- Нічний режим ----------

    private fun pickNightWindow() {
        val current = prefs.pendingNightWindow() ?: prefs.nightWindow()
        TimePickerDialog(this, { _, h1, m1 ->
            val start = h1 * 60 + m1
            TimePickerDialog(this, { _, h2, m2 ->
                val end = h2 * 60 + m2
                if (start == end) {
                    Toast.makeText(this, getString(R.string.toast_night_same), Toast.LENGTH_LONG).show()
                } else {
                    showResult(prefs.setNightWindow(NightWindow(start, end)))
                    refreshStatus()
                }
            }, current.end / 60, current.end % 60, true).apply {
                setTitle(getString(R.string.night_to_title))
            }.show()
        }, current.start / 60, current.start % 60, true).apply {
            setTitle(getString(R.string.night_from_title))
        }.show()
    }

    // ---------- Діалог правил ----------

    private fun minutesField(value: Int?, hintText: String) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        hint = hintText
        setText(value?.toString() ?: "")
        setSelectAllOnFocus(true)
    }

    /** Ряд швидких кнопок: 5 хв, 10, 15, 30, 45, 1 год. */
    private fun presets(target: EditText) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in PRESET_MINUTES) {
            row.addView(Button(this@MainActivity).apply {
                text = formatMinutes(this@MainActivity, m)
                textSize = 12f
                setAllCaps(false)
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener {
                    target.setText(m.toString())
                    target.setSelection(target.text.length)
                }
            })
        }
        addView(row)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(12), 0, 0)
    }

    private fun showLimitDialog(app: AppItem) {
        val current = prefs.rules()[app.pkg]
        val shown = prefs.pendingRules()[app.pkg] ?: current ?: Rule()

        val weekdayInput = minutesField(shown.weekday, getString(R.string.hint_no_limit))
        val weekendInput = minutesField(shown.weekend, getString(R.string.hint_same_as_weekdays))
        val opensInput = minutesField(shown.opens, getString(R.string.hint_no_limit))
        val nightBox = CheckBox(this).apply {
            val w = prefs.nightWindow()
            text = getString(R.string.night_checkbox, formatTime(w.start), formatTime(w.end))
            isChecked = shown.night
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(label(getString(R.string.label_weekday)))
            addView(weekdayInput)
            addView(presets(weekdayInput))
            addView(label(getString(R.string.label_weekend)))
            addView(weekendInput)
            addView(presets(weekendInput))
            addView(label(getString(R.string.label_opens)))
            addView(opensInput)
            addView(nightBox.apply { setPadding(0, dp(12), 0, 0) })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.rule_footer)
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(12), 0, dp(8))
            })
        }

        val builder = AlertDialog.Builder(this)
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
        if (current != null) {
            builder.setNeutralButton(R.string.btn_remove_all) { _, _ -> applyRule(app.pkg, Rule()) }
        }
        builder.show()
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
                }
                val subtitle = TextView(this@MainActivity).apply { textSize = 13f }
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                    addView(name)
                    addView(subtitle)
                }
                row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
                    addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
                    blocked -> Color.parseColor("#C92A2A")
                    rule != null -> Color.parseColor("#3B5BDB")
                    else -> Color.GRAY
                }
            )
            return row
        }
    }

    // ---------- Допоміжне ----------

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun permissionRow(status: TextView, button: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        status.textSize = 15f
        addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(button)
    }

    companion object {
        private const val REQ_NOTIFICATIONS = 1
        private val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60)

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
