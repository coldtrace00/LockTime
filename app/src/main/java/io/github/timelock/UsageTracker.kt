package io.github.timelock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** Використання додатка за сьогодні. */
data class AppUsage(val timeMs: Long = 0L, val opens: Int = 0)

/** Рахує, скільки часу від опівночі кожен додаток був на екрані і скільки разів його відкривали. */
object UsageTracker {

    // Значення констант UsageEvents.Event (однакові на всіх версіях Android).
    private const val ACTIVITY_RESUMED = 1
    private const val ACTIVITY_PAUSED = 2
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val ACTIVITY_STOPPED = 23

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * @param packages які додатки рахувати
     * @param foregroundPkg додаток, що зараз на екрані: лише для нього
     *        незакрита сесія рахується до поточного моменту
     */
    fun usageToday(
        context: Context,
        packages: Set<String>,
        foregroundPkg: String?
    ): Map<String, AppUsage> {
        if (packages.isEmpty()) return emptyMap()

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfDay(LocalDate.now())
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(start, now)
        } catch (e: SecurityException) {
            null
        } ?: return emptyMap()

        val time = HashMap<String, Long>()
        val opens = HashMap<String, Int>()
        val openSince = HashMap<String, Long>()
        val seen = HashSet<String>()
        var lastResumed: String? = null
        val event = UsageEvents.Event()

        fun close(pkg: String, at: Long) {
            val since = openSince.remove(pkg) ?: return
            time[pkg] = (time[pkg] ?: 0L) + (at - since)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val ts = event.timeStamp
            when (event.eventType) {
                ACTIVITY_RESUMED -> {
                    // На екрані може бути лише один додаток: решту закриваємо.
                    for (other in openSince.keys.toList()) if (other != pkg) close(other, ts)
                    if (pkg in packages) {
                        if (!openSince.containsKey(pkg)) openSince[pkg] = ts
                        // Відкриття — це перехід у додаток з іншого додатка чи з вимкненого екрана,
                        // а не перехід між екранами всередині нього.
                        if (lastResumed != pkg) opens[pkg] = (opens[pkg] ?: 0) + 1
                    }
                    lastResumed = pkg
                    seen.add(pkg)
                }
                ACTIVITY_PAUSED, ACTIVITY_STOPPED -> {
                    if (pkg in packages) {
                        if (!openSince.containsKey(pkg) && pkg !in seen) {
                            // Додаток був відкритий ще до опівночі.
                            openSince[pkg] = start
                        }
                        close(pkg, ts)
                    }
                    seen.add(pkg)
                }
                SCREEN_NON_INTERACTIVE -> {
                    for (other in openSince.keys.toList()) close(other, ts)
                    lastResumed = null
                }
            }
        }

        if (foregroundPkg != null) {
            val since = openSince[foregroundPkg]
            if (since != null) {
                time[foregroundPkg] = (time[foregroundPkg] ?: 0L) + (now - since)
            }
        }

        return packages.associateWith { AppUsage(time[it] ?: 0L, opens[it] ?: 0) }
    }

    /** Сумарний час на екрані за останні [days] днів (включно з сьогодні): пакет -> мс. */
    fun usageForDays(context: Context, days: Int): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfDay(LocalDate.now().minusDays((days - 1).toLong()))
        val stats = try {
            usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
        } catch (e: SecurityException) {
            null
        } ?: return emptyMap()
        return stats.mapValues { it.value.totalTimeInForeground }.filterValues { it > 0L }
    }
}
