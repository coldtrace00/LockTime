package io.github.timelock

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Скільки часу пішло в кожен додаток за останні 7 днів. */
class StatsActivity : Activity() {

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Статистика за тиждень"

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        list.addView(TextView(this).apply {
            text = "Рахую…"
            textSize = 15f
        })
        setContentView(ScrollView(this).apply { addView(list) })

        Thread { load() }.start()
    }

    private class Row(val label: String, val icon: android.graphics.drawable.Drawable?, val ms: Long, val limited: Boolean)

    private fun load() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val launchable = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet()
        val limited = Prefs(this).rules().keys

        val rows = UsageTracker.usageForDays(this, DAYS)
            .filterKeys { it in launchable && it != packageName }
            .entries
            .sortedByDescending { it.value }
            .take(MAX_ROWS)
            .mapNotNull { (pkg, ms) ->
                try {
                    @Suppress("DEPRECATION")
                    val info = pm.getApplicationInfo(pkg, 0)
                    Row(pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info), ms, pkg in limited)
                } catch (e: Exception) {
                    null
                }
            }
        runOnUiThread { show(rows) }
    }

    private fun show(rows: List<Row>) {
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Немає даних. Перевірте, чи надано дозвіл «Статистика використання»."
                textSize = 15f
            })
            return
        }

        val total = rows.sumOf { it.ms }
        list.addView(TextView(this).apply {
            text = "За останні $DAYS днів: ${formatMs(total)}"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        list.addView(TextView(this).apply {
            text = "У середньому ${formatMs(total / DAYS)} на день. Синім — додатки з лімітом."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        val max = rows.first().ms.coerceAtLeast(1L)
        for (r in rows) {
            val color = if (r.limited) Color.parseColor("#3B5BDB") else Color.parseColor("#868E96")
            val icon = ImageView(this).apply { setImageDrawable(r.icon) }
            val name = TextView(this).apply {
                text = r.label
                textSize = 15f
            }
            val detail = TextView(this).apply {
                text = "${formatMs(r.ms)} · ${formatMs(r.ms / DAYS)} на день"
                textSize = 12f
                setTextColor(color)
            }
            val fraction = r.ms.toFloat() / max
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(View(this@StatsActivity).apply { setBackgroundColor(color) },
                    LinearLayout.LayoutParams(0, dp(6), fraction))
                addView(View(this@StatsActivity),
                    LinearLayout.LayoutParams(0, dp(6), 1f - fraction))
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(name)
                addView(detail)
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                    topMargin = dp(4)
                })
            }
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                addView(icon, LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
    }

    private fun formatMs(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        return if (minutes < 1) "<1 хв" else MainActivity.formatMinutes(minutes)
    }

    companion object {
        private const val DAYS = 7
        private const val MAX_ROWS = 25
    }
}
