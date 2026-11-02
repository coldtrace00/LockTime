package io.github.timelock

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.ComponentName
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        header.addView(sectionTitle("Дозволи"))

        usageStatus = TextView(this)
        usageButton = Button(this).apply {
            text = "Надати"
            setOnClickListener { openUsageAccess() }
        }
        header.addView(permissionRow(usageStatus, usageButton))

        a11yStatus = TextView(this)
        a11yButton = Button(this).apply {
            text = "Увімкнути"
            setOnClickListener { showAccessibilityHelp() }
        }
        header.addView(permissionRow(a11yStatus, a11yButton))

        notifStatus = TextView(this)
        notifButton = Button(this).apply {
            text = "Дозволити"
            setOnClickListener { requestNotifications() }
        }
        header.addView(permissionRow(notifStatus, notifButton))

        batteryStatus = TextView(this)
        batteryButton = Button(this).apply {
            text = "Вимкнути"
            setOnClickListener { openBatterySettings() }
        }
        header.addView(permissionRow(batteryStatus, batteryButton))

        header.addView(sectionTitle("Захист від видалення"))
        protectionSwitch = Switch(this).apply {
            text = "Не давати видалити чи вимкнути LockTime"
            textSize = 16f
        }
        header.addView(protectionSwitch)
        protectionNote = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, 0)
        }
        header.addView(protectionNote)

        header.addView(sectionTitle("Нічний режим"))
        nightText = TextView(this).apply { textSize = 14f }
        header.addView(nightText)
        header.addView(Button(this).apply {
            text = "Змінити час"
            setOnClickListener { pickNightWindow() }
        })

        header.addView(sectionTitle("Статистика"))
        header.addView(Button(this).apply {
            text = "Статистика за тиждень"
            setOnClickListener { startActivity(Intent(this@MainActivity, StatsActivity::class.java)) }
        })

        header.addView(sectionTitle("Додатки"))
        header.addView(TextView(this).apply {
            text = "Торкніться додатка, щоб задати ліміти. Посилити можна одразу, " +
                "а послабити чи прибрати — лише з наступного дня."
            textSize = 13f
            setTextColor(Color.GRAY)
        })
        loadingText = TextView(this).apply {
            text = "Завантажую список додатків…"
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
        usageStatus.text = (if (hasUsage) "✅ " else "❌ ") + "Статистика використання"
        usageButton.visibility = if (hasUsage) View.GONE else View.VISIBLE

        val hasA11y = isAccessibilityEnabled()
        a11yStatus.text = (if (hasA11y) "✅ " else "❌ ") + "Служба спеціальних можливостей"
        a11yButton.visibility = if (hasA11y) View.GONE else View.VISIBLE

        val notif = notificationsEnabled()
        notifStatus.text = (if (notif) "✅ " else "⚠️ ") + "Сповіщення (за 15/10/5 хв)"
        notifButton.visibility = if (notif) View.GONE else View.VISIBLE

        val pm = getSystemService(PowerManager::class.java)
        val noBatteryLimit = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatus.text = (if (noBatteryLimit) "✅ " else "⚠️ ") + "Без оптимізації батареї"
        batteryButton.visibility = if (noBatteryLimit) View.GONE else View.VISIBLE

        protectionSwitch.setOnCheckedChangeListener(null)
        protectionSwitch.isChecked = prefs.protectionEnabled() && prefs.protectionOffAt() == null
        protectionSwitch.setOnCheckedChangeListener { _, checked -> onProtectionToggled(checked) }
        val w = prefs.nightWindow()
        val nightApps = prefs.rules().values.count { it.night }
        val pendingNight = prefs.pendingNightWindow()
        nightText.text = buildString {
            append("З ").append(formatTime(w.start)).append(" до ").append(formatTime(w.end))
            append(" блокуються додатки з позначкою «вночі»")
            append(if (nightApps == 0) " (поки жодного)." else " ($nightApps).")
            if (w.coversNow() && nightApps > 0) append("\nЗараз нічний режим діє.")
            if (pendingNight != null) {
                append("\nЗ завтра: ").append(formatTime(pendingNight.start))
                    .append("–").append(formatTime(pendingNight.end)).append(".")
            }
        }

        val offAt = prefs.protectionOffAt()
        protectionNote.text = when {
            offAt != null -> "Захист буде знято орієнтовно ${formatDateTime(offAt)}. Після цього " +
                "LockTime можна видалити. Передумали — увімкніть перемикач знову."
            prefs.protectionEnabled() -> "Увімкнено: екрани налаштувань, де можна видалити, зупинити " +
                "чи вимкнути LockTime, одразу закриваються."
            else -> "Коли увімкнено, LockTime не можна видалити, зупинити чи вимкнути в налаштуваннях. " +
                "Вимкнути захист можна тут — подіє через 72 години."
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
        Toast.makeText(this, "Знайдіть LockTime у списку й увімкніть", Toast.LENGTH_LONG).show()
    }

    private fun showAccessibilityHelp() {
        AlertDialog.Builder(this)
            .setTitle("Служба спеціальних можливостей")
            .setMessage(
                "Вона потрібна, щоб помітити відкриття Instagram, TikTok тощо і закрити їх, " +
                    "коли час вийшов.\n\n" +
                    "Samsung: Налаштування → Спеціальні можливості → Установлені програми → " +
                    "LockTime → увімкнути.\n\n" +
                    "Якщо перемикач сірий і пише «Обмежене налаштування» — натисніть " +
                    "«Інформація про додаток», там ⋮ (угорі праворуч) → " +
                    "«Дозволити обмежені налаштування», і поверніться сюди."
            )
            .setPositiveButton("Відкрити") { _, _ ->
                safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Інформація про додаток") { _, _ ->
                safeStart(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton("Скасувати", null)
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
                .setTitle("Увімкнути захист?")
                .setMessage(
                    "Поки захист увімкнено, сторінки налаштувань з LockTime (видалення, " +
                        "примусова зупинка, вимкнення служби) будуть одразу закриватися.\n\n" +
                        "Вимкнути захист можна лише тут, і подіє це через 72 години."
                )
                .setPositiveButton("Увімкнути") { _, _ ->
                    prefs.setProtection(true)
                    refreshStatus()
                }
                .setNegativeButton("Скасувати") { _, _ -> refreshStatus() }
                .setOnCancelListener { refreshStatus() }
                .show()
        } else {
            val offAt = prefs.setProtection(false)
            refreshStatus()
            if (offAt != null) {
                AlertDialog.Builder(this)
                    .setTitle("Захист буде знято через 72 години")
                    .setMessage(
                        "Видалити LockTime можна буде ${formatDateTime(offAt)}.\n\n" +
                            "Відлік іде за внутрішнім таймером, тож переведення годинника не допоможе. " +
                            "Якщо телефон буде вимкнений, цей час не зараховується і дата зсунеться.\n\n" +
                            "До того часу захист працює як і раніше. Якщо передумаєте — " +
                            "увімкніть перемикач знову, і відлік скасується."
                    )
                    .setPositiveButton("Зрозуміло", null)
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
                    Toast.makeText(this, "Початок і кінець не можуть збігатися", Toast.LENGTH_LONG).show()
                } else {
                    showResult(prefs.setNightWindow(NightWindow(start, end)))
                    refreshStatus()
                }
            }, current.end / 60, current.end % 60, true).apply {
                setTitle("До котрої години")
            }.show()
        }, current.start / 60, current.start % 60, true).apply {
            setTitle("З котрої години")
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
                text = formatMinutes(m)
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

        val weekdayInput = minutesField(shown.weekday, "без обмеження")
        val weekendInput = minutesField(shown.weekend, "як у будні")
        val opensInput = minutesField(shown.opens, "без обмеження")
        val nightBox = CheckBox(this).apply {
            val w = prefs.nightWindow()
            text = "Блокувати в нічний режим (${formatTime(w.start)}–${formatTime(w.end)})"
            isChecked = shown.night
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), 0)
            addView(label("Хвилин на день у будні"))
            addView(weekdayInput)
            addView(presets(weekdayInput))
            addView(label("Хвилин на день у вихідні (сб, нд)"))
            addView(weekendInput)
            addView(presets(weekendInput))
            addView(label("Скільки разів на день можна відкрити"))
            addView(opensInput)
            addView(nightBox.apply { setPadding(0, dp(12), 0, 0) })
            addView(TextView(this@MainActivity).apply {
                text = "Порожнє поле — без обмеження. Посилити можна одразу, послабити — з завтра."
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(12), 0, dp(8))
            })
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(app.label)
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("Зберегти") { _, _ ->
                fun parse(e: EditText) = e.text.toString().toIntOrNull()?.takeIf { it > 0 }
                val rule = Rule(
                    weekday = parse(weekdayInput)?.coerceAtMost(Prefs.MAX_MINUTES),
                    weekend = parse(weekendInput)?.coerceAtMost(Prefs.MAX_MINUTES),
                    opens = parse(opensInput),
                    night = nightBox.isChecked
                )
                applyRule(app.pkg, rule)
            }
            .setNegativeButton("Скасувати", null)
        if (current != null) {
            builder.setNeutralButton("Прибрати все") { _, _ -> applyRule(app.pkg, Rule()) }
        }
        builder.show()
    }

    private fun applyRule(pkg: String, rule: Rule) {
        showResult(prefs.setRule(pkg, rule))
        if (!isAccessibilityEnabled() || !hasUsageAccess()) {
            Toast.makeText(this, "Щоб блокування працювало, надайте дозволи вгорі", Toast.LENGTH_LONG).show()
        }
        sortApps()
        refreshUsage()
        refreshStatus()
        adapter.notifyDataSetChanged()
    }

    private fun showResult(result: Prefs.Result) {
        val msg = when (result) {
            Prefs.Result.APPLIED -> "Збережено"
            Prefs.Result.PARTLY -> "Суворіші зміни вже діють, послаблення — з завтра"
            Prefs.Result.TOMORROW -> "Послаблення запрацює завтра"
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
                parts.add("будні ${formatMinutes(wd)}")
                parts.add("вихідні ${formatMinutes(we)}")
            }
            we != null -> parts.add("вихідні ${formatMinutes(we)}")
            wd != null -> parts.add(formatMinutes(wd) + " на день")
        }
        rule.opens?.let { parts.add("до $it відкр.") }
        if (rule.night) parts.add("вночі")
        return if (parts.isEmpty()) "без обмежень" else parts.joinToString(" · ")
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
                sb.append("Без ліміту")
            } else {
                val limitMin = rule.minutesFor(LocalDate.now())
                val maxOpens = rule.opens
                val status = when {
                    rule.night && prefs.nightWindow().coversNow() -> "🌙 Нічний режим"
                    maxOpens != null && used.opens >= maxOpens -> "⛔ Відкриття вичерпано"
                    limitMin != null && used.timeMs >= limitMin * 60_000L -> "⛔ Заблоковано до опівночі"
                    else -> null
                }
                if (status != null) {
                    blocked = true
                    sb.append(status).append("\n")
                }
                sb.append(describe(rule))
                sb.append("\nСьогодні: ").append(formatMinutes((used.timeMs / 60_000L).toInt()))
                sb.append(", відкрито ").append(used.opens).append(" р.")
            }
            if (pending != null) {
                sb.append("\nЗ завтра: ").append(describe(pending))
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
            java.text.SimpleDateFormat("d.MM.yyyy 'о' HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(millis))

        fun formatMinutes(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h == 0 -> "$m хв"
                m == 0 -> "$h год"
                else -> "$h год $m хв"
            }
        }
    }
}
