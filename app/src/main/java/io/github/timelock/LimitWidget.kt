package io.github.timelock

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.LocalDate

/**
 * Віджет на робочий стіл: скільки часу й відкриттів лишилось сьогодні
 * в додатках з лімітами. Оновлюється службою раз на хвилину, а системою —
 * раз на 30 хв про запас. Натискання відкриває LockTime.
 */
class LimitWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context)
    }

    companion object {
        private const val MAX_LINES = 5

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, LimitWidget::class.java))

        fun hasWidgets(context: Context): Boolean = ids(context).isNotEmpty()

        fun update(context: Context) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            val l = Lang.wrap(context)

            val views = RemoteViews(context.packageName, R.layout.widget)
            views.setTextViewText(R.id.widget_title, l.getString(R.string.widget_title))
            views.setTextViewText(R.id.widget_body, body(context, l))
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views)
        }

        private fun body(context: Context, l: Context): String {
            val prefs = Prefs(context)
            val rules = prefs.rules()
            if (rules.isEmpty()) return l.getString(R.string.widget_empty)

            val usage = UsageTracker.usageToday(context, rules.keys, null)
            val nightNow = prefs.nightWindow().coversNow()
            val today = LocalDate.now()
            val pm = context.packageManager

            return rules.entries
                .map { (pkg, rule) ->
                    val label = try {
                        @Suppress("DEPRECATION")
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    } catch (e: Exception) {
                        null
                    }
                    Triple(pkg, rule, label)
                }
                .filter { it.third != null } // видалені додатки не показуємо
                .sortedBy { it.third!!.lowercase() }
                .take(MAX_LINES)
                .joinToString("\n") { (pkg, rule, label) ->
                    val u = usage[pkg] ?: AppUsage()
                    val limit = rule.minutesFor(today)
                    val maxOpens = rule.opens
                    val status = when {
                        rule.night && nightNow -> l.getString(R.string.widget_night)
                        maxOpens != null && u.opens >= maxOpens -> l.getString(R.string.widget_opens_out)
                        limit != null && u.timeMs >= limit * 60_000L -> l.getString(R.string.widget_blocked)
                        else -> {
                            val parts = mutableListOf<String>()
                            if (limit != null) {
                                val leftMin = ((limit * 60_000L - u.timeMs) / 60_000L).toInt().coerceAtLeast(1)
                                parts.add(l.getString(R.string.widget_time_left, MainActivity.formatMinutes(l, leftMin)))
                            }
                            if (maxOpens != null) {
                                parts.add(l.getString(R.string.widget_opens_left, maxOpens - u.opens))
                            }
                            if (parts.isEmpty()) l.getString(R.string.widget_no_limit) else parts.joinToString(" · ")
                        }
                    }
                    "$label — $status"
                }
        }
    }
}
