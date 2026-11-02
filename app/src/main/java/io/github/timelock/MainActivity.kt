package io.github.timelock

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

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

    private var apps: List<AppItem> = emptyList()
    private var usage: Map<String, Long> = emptyMap()

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

        header.addView(sectionTitle("Додатки"))
        header.addView(TextView(this).apply {
            text = "Торкніться додатка, щоб задати денний ліміт. Посилити ліміт можна одразу, " +
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
        val offAt = prefs.protectionOffAt()
        protectionNote.text = when {
            offAt != null -> "Захист буде знято ${formatDateTime(offAt)}. Після цього LockTime можна " +
                "видалити. Передумали — увімкніть перемикач знову."
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
        val limited = prefs.activeLimits().keys + prefs.pendingLimits().keys
        apps = apps.sortedWith(
            compareBy<AppItem>({ if (it.pkg in limited) 0 else 1 }, { it.label.lowercase() })
        )
    }

    private fun refreshUsage() {
        usage = if (hasUsageAccess()) {
            UsageTracker.usageToday(this, prefs.activeLimits().keys, null)
        } else {
            emptyMap()
        }
    }

    private fun showLimitDialog(app: AppItem) {
        val current = prefs.activeLimits()[app.pkg]
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "хвилин на день"
            setText((current ?: 60).toString())
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(app.label)
            .setMessage(
                "Скільки хвилин на день можна користуватися цим додатком?\n" +
                    "Наприклад: 60 = 1 година."
            )
            .setView(box)
            .setPositiveButton("Зберегти") { _, _ ->
                val minutes = input.text.toString().toIntOrNull()
                if (minutes == null || minutes <= 0) {
                    Toast.makeText(this, "Введіть кількість хвилин, більшу за 0", Toast.LENGTH_SHORT).show()
                } else {
                    applyLimit(app.pkg, minutes)
                }
            }
            .setNegativeButton("Скасувати", null)
        if (current != null) {
            builder.setNeutralButton("Прибрати ліміт") { _, _ -> applyLimit(app.pkg, 0) }
        }
        builder.show()
    }

    private fun applyLimit(pkg: String, minutes: Int) {
        when (prefs.setLimit(pkg, minutes)) {
            Prefs.Result.APPLIED ->
                Toast.makeText(this, "Збережено", Toast.LENGTH_SHORT).show()
            Prefs.Result.TOMORROW ->
                Toast.makeText(this, "Послаблення ліміту запрацює завтра", Toast.LENGTH_LONG).show()
            Prefs.Result.UNCHANGED -> {}
        }
        if (!isAccessibilityEnabled() || !hasUsageAccess()) {
            Toast.makeText(this, "Щоб блокування працювало, надайте дозволи вгорі", Toast.LENGTH_LONG).show()
        }
        sortApps()
        refreshUsage()
        adapter.notifyDataSetChanged()
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

            val limit = prefs.activeLimits()[app.pkg]
            val pending = prefs.pendingLimits()
            val used = usage[app.pkg] ?: 0L
            val blocked = limit != null && used >= limit * 60_000L

            val sb = StringBuilder()
            if (limit == null) {
                sb.append("Без ліміту")
            } else {
                if (blocked) sb.append("⛔ Заблоковано до опівночі · ")
                sb.append("Ліміт: ").append(formatMinutes(limit))
                sb.append(" · сьогодні: ").append(formatMinutes((used / 60_000L).toInt()))
            }
            if (pending.containsKey(app.pkg)) {
                val p = pending[app.pkg] ?: 0
                sb.append("\nЗ завтра: ").append(if (p <= 0) "без ліміту" else formatMinutes(p))
            }
            holder.subtitle.text = sb.toString()
            holder.subtitle.setTextColor(
                when {
                    blocked -> Color.parseColor("#C92A2A")
                    limit != null -> Color.parseColor("#3B5BDB")
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
