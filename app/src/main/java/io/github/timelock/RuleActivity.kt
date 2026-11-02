package io.github.timelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate

/**
 * Повноекранне налаштування обмежень одного додатка:
 * час на день (щодня / будні й вихідні / по днях), відкриття, нічний режим.
 */
class RuleActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var pkg: String

    private var mode = MODE_SAME
    private lateinit var sameBox: View
    private lateinit var weekBox: View
    private lateinit var daysBox: View
    private val modeButtons = mutableListOf<Pair<Int, TextView>>()

    private lateinit var sameInput: EditText
    private lateinit var weekdayInput: EditText
    private lateinit var weekendInput: EditText
    private val dayInputs = mutableListOf<EditText>()
    private lateinit var opensInput: EditText
    private lateinit var nightSwitch: Switch

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Look.themeResNoBar(this))
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        styleSystemBars()

        val current = prefs.rules()[pkg]
        val shown = prefs.pendingRules()[pkg] ?: current ?: Rule()
        val m = shown.minutes
        mode = when {
            m.all { it == m[0] } -> MODE_SAME
            shown.isWeekPattern -> MODE_WEEK
            else -> MODE_DAYS
        }

        val (label, icon) = appInfo(pkg)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }

        // ---- Шапка: назад, іконка, назва, сьогоднішнє використання ----
        page.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(Look.text(this@RuleActivity))
            setPadding(dp(4), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        page.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), 0, dp(8))
            if (icon != null) addView(ImageView(this@RuleActivity).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(LinearLayout(this@RuleActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(TextView(this@RuleActivity).apply {
                    text = label
                    textSize = 24f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Look.text(this@RuleActivity))
                })
                val u = UsageTracker.usageToday(this@RuleActivity, setOf(pkg), null)[pkg] ?: AppUsage()
                addView(muted(13f).apply {
                    text = getString(
                        R.string.row_today,
                        MainActivity.formatMinutes(this@RuleActivity, (u.timeMs / 60_000L).toInt()), u.opens
                    )
                })
            })
        })

        // ---- Час на день ----
        page.addView(group(getString(R.string.dlg_time)))
        page.addView(card().apply {
            addView(muted(13f).apply { text = getString(R.string.dlg_time_hint) })
            addView(modeSwitcher(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })

            sameInput = numberInput(m[0])
            sameBox = LinearLayout(this@RuleActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(numberBox(sameInput, getString(R.string.suffix_min)), boxParams())
                addView(chipGrid(sameInput, PRESET_MINUTES, 3, withNone = true) { minutes(it) })
            }
            addView(sameBox)

            weekdayInput = numberInput(m[0])
            weekendInput = numberInput(m[5])
            weekBox = LinearLayout(this@RuleActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(fieldLabel(getString(R.string.dlg_weekdays)))
                addView(numberBox(weekdayInput, getString(R.string.suffix_min)), boxParams())
                addView(chipGrid(weekdayInput, PRESET_MINUTES, 3, withNone = true) { minutes(it) })
                addView(fieldLabel(getString(R.string.dlg_weekends)))
                addView(numberBox(weekendInput, getString(R.string.suffix_min)), boxParams())
                addView(chipGrid(weekendInput, PRESET_MINUTES, 3, withNone = true) { minutes(it) })
            }
            addView(weekBox)

            val names = getString(R.string.days_short).split(",")
            daysBox = LinearLayout(this@RuleActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, 0)
                for (i in 0 until 7) {
                    val input = numberInput(m[i])
                    dayInputs.add(input)
                    addView(LinearLayout(this@RuleActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(this@RuleActivity).apply {
                            text = names.getOrElse(i) { "" }
                            textSize = 15f
                            setTextColor(Look.text(this@RuleActivity))
                        }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
                        addView(numberBox(input, getString(R.string.suffix_min)),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) })
                }
            }
            addView(daysBox)
        }, cardParams())
        applyMode()

        // ---- Відкриття ----
        page.addView(group(getString(R.string.dlg_opens)))
        opensInput = numberInput(shown.opens)
        page.addView(card().apply {
            addView(muted(13f).apply { text = getString(R.string.dlg_opens_hint) })
            addView(numberBox(opensInput, getString(R.string.suffix_times)), boxParams())
            addView(chipGrid(opensInput, PRESET_OPENS, 5, withNone = true) { it.toString() })
        }, cardParams())

        // ---- Нічний режим ----
        page.addView(group(getString(R.string.section_night)))
        val w = prefs.nightWindow()
        nightSwitch = Switch(this).apply {
            text = getString(R.string.night_checkbox, MainActivity.formatTime(w.start), MainActivity.formatTime(w.end))
            textSize = 15f
            setTextColor(Look.text(this@RuleActivity))
            isChecked = shown.night
        }
        page.addView(card().apply {
            addView(nightSwitch)
            addView(muted(13f).apply {
                text = getString(R.string.rule_night_hint)
                setPadding(0, dp(6), 0, 0)
            })
        }, cardParams())

        page.addView(muted(13f).apply {
            text = getString(R.string.rule_footer)
            setPadding(dp(4), dp(14), dp(4), 0)
        })

        if (current != null) {
            page.addView(TextView(this).apply {
                text = getString(R.string.btn_remove_limits)
                textSize = 15f
                gravity = Gravity.CENTER
                setTextColor(Look.danger(this@RuleActivity))
                setPadding(0, dp(18), 0, dp(8))
                setOnClickListener { save(Rule()) }
            })
        }

        // ---- Нижня панель: завжди видно, навіть з клавіатурою ----
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Look.navBg(this@RuleActivity))
            setPadding(dp(16), dp(10), dp(16), dp(12))
            addView(button(getString(R.string.btn_cancel), primary = false) { finish() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button(getString(R.string.btn_save), primary = true) { save(collect()) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Look.bg(this@RuleActivity))
            addView(ScrollView(this@RuleActivity).apply { addView(page) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@RuleActivity).apply { setBackgroundColor(Look.divider(this@RuleActivity)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
            addView(bottom)
        })
    }

    // ---------- Режим часу ----------

    private fun modeSwitcher() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = Look.rounded(Look.chip(this@RuleActivity), dpf(18))
        setPadding(dp(3), dp(3), dp(3), dp(3))
        for ((code, label) in listOf(
            MODE_SAME to R.string.mode_same, MODE_WEEK to R.string.mode_week, MODE_DAYS to R.string.mode_days
        )) {
            val b = TextView(this@RuleActivity).apply {
                text = getString(label)
                textSize = 13f
                gravity = Gravity.CENTER
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { switchMode(code) }
            }
            modeButtons.add(code to b)
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** Перемикаючи режим, переносимо вже введені значення, щоб нічого не губилось. */
    private fun switchMode(newMode: Int) {
        if (newMode == mode) return
        val days = currentDays()
        when (newMode) {
            MODE_SAME -> sameInput.setText(days[0]?.toString() ?: "")
            MODE_WEEK -> {
                weekdayInput.setText(days[0]?.toString() ?: "")
                weekendInput.setText(days[5]?.toString() ?: "")
            }
            MODE_DAYS -> for (i in 0 until 7) dayInputs[i].setText(days[i]?.toString() ?: "")
        }
        mode = newMode
        applyMode()
    }

    private fun applyMode() {
        sameBox.visibility = if (mode == MODE_SAME) View.VISIBLE else View.GONE
        weekBox.visibility = if (mode == MODE_WEEK) View.VISIBLE else View.GONE
        daysBox.visibility = if (mode == MODE_DAYS) View.VISIBLE else View.GONE
        for ((code, b) in modeButtons) {
            val on = code == mode
            b.background = if (on) Look.rounded(Look.ACCENT_FILL, dpf(15)) else null
            b.setTextColor(if (on) Look.WHITE else Look.text(this))
        }
    }

    private fun parse(e: EditText): Int? =
        e.text.toString().toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(Prefs.MAX_MINUTES)

    /** Хвилини на кожен день тижня за поточним режимом. */
    private fun currentDays(): List<Int?> = when (mode) {
        MODE_SAME -> parse(sameInput).let { v -> List(7) { v } }
        MODE_WEEK -> {
            val wd = parse(weekdayInput)
            val we = parse(weekendInput)
            List(7) { if (it < 5) wd else we }
        }
        else -> dayInputs.map { parse(it) }
    }

    private fun collect(): Rule = Rule(
        currentDays(),
        opensInput.text.toString().toIntOrNull()?.takeIf { it > 0 },
        nightSwitch.isChecked
    ).normalized()

    private fun save(rule: Rule) {
        val msg = when (prefs.setRule(pkg, rule)) {
            Prefs.Result.APPLIED -> getString(R.string.result_applied)
            Prefs.Result.PARTLY -> getString(R.string.result_partly)
            Prefs.Result.TOMORROW -> getString(R.string.result_tomorrow)
            Prefs.Result.UNCHANGED -> null
        }
        msg?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        LimitWidget.update(this)
        finish()
    }

    // ---------- Будівельні блоки ----------

    private fun numberInput(value: Int?) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value?.toString() ?: "")
        hint = getString(R.string.chip_none)
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Look.text(this@RuleActivity))
        setHintTextColor(Look.muted(this@RuleActivity))
        background = null
        setSingleLine(true)
        setSelectAllOnFocus(true)
        setPadding(0, 0, 0, 0)
    }

    /** Велике поле з числом і підписом «хв» / «раз» у заокругленій рамці. */
    private fun numberBox(input: EditText, suffix: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Look.rounded(Look.bg(this@RuleActivity), dpf(12))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(muted(15f).apply { text = suffix })
        setOnClickListener { input.requestFocus() }
    }

    /**
     * Сітка чипів: усі видно одразу, без прокрутки вбік.
     * Чип зі значенням у полі підсвічено; «Без ліміту» очищає поле.
     */
    private fun chipGrid(target: EditText, values: List<Int>, columns: Int, withNone: Boolean, label: (Int) -> String): View {
        val all = values.map { it as Int? } + if (withNone) listOf(null) else emptyList()
        val chips = mutableListOf<Pair<Int?, TextView>>()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        all.chunked(columns).forEach { rowValues ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                val v = rowValues.getOrNull(i)
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(6)
                }
                if (i >= rowValues.size) {
                    row.addView(View(this), params)
                    continue
                }
                val chip = TextView(this).apply {
                    text = if (v == null) getString(R.string.chip_none) else label(v)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setSingleLine(true)
                    setPadding(dp(4), dp(9), dp(4), dp(9))
                    setOnClickListener {
                        target.setText(v?.toString() ?: "")
                        target.setSelection(target.text.length)
                    }
                }
                chips.add(v to chip)
                row.addView(chip, params)
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        fun refresh() {
            val cur = target.text.toString().toIntOrNull()?.takeIf { it > 0 }
            for ((v, chip) in chips) {
                val on = v == cur
                chip.background = Look.rounded(if (on) Look.ACCENT_FILL else Look.chip(this), dpf(12))
                chip.setTextColor(if (on) Look.WHITE else Look.text(this))
            }
        }
        target.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refresh()
        })
        refresh()
        return grid
    }

    private fun minutes(v: Int) = MainActivity.formatMinutes(this, v)

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Look.rounded(Look.card(this@RuleActivity), dpf(16))
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    private fun boxParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(12) }

    private fun group(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Look.muted(this@RuleActivity))
        setPadding(dp(4), dp(18), 0, dp(2))
    }

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Look.text(this@RuleActivity))
        setPadding(0, dp(14), 0, 0)
    }

    private fun muted(size: Float) = TextView(this).apply {
        textSize = size
        setTextColor(Look.muted(this@RuleActivity))
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Look.WHITE else Look.accentText(this@RuleActivity))
        background = Look.rounded(if (primary) Look.ACCENT_FILL else Look.chip(this@RuleActivity), dpf(22))
        setPadding(0, dp(13), 0, dp(13))
        setOnClickListener { onClick() }
    }

    private fun appInfo(pkg: String): Pair<String, Drawable?> =
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString() to packageManager.getApplicationIcon(info)
        } catch (e: Exception) {
            pkg to null
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

    companion object {
        const val EXTRA_PACKAGE = "package"
        private const val MODE_SAME = 0
        private const val MODE_WEEK = 1
        private const val MODE_DAYS = 2
        private val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60)
        private val PRESET_OPENS = listOf(3, 5, 10, 20)

        fun open(context: Context, pkg: String) {
            context.startActivity(Intent(context, RuleActivity::class.java).putExtra(EXTRA_PACKAGE, pkg))
        }
    }
}
