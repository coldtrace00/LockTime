package io.github.timelock

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/** Рахує, скільки часу від опівночі кожен додаток був на екрані. */
object UsageTracker {

    // Значення констант UsageEvents.Event (однакові на всіх версіях Android).
    private const val ACTIVITY_RESUMED = 1
    private const val ACTIVITY_PAUSED = 2
    private const val SCREEN_NON_INTERACTIVE = 16
    private const val ACTIVITY_STOPPED = 23

    fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /**
     * @param packages які додатки рахувати
     * @param foregroundPkg додаток, що зараз на екрані: лише для нього
     *        незакрита сесія рахується до поточного моменту
     * @return пакет -> мілісекунд сьогодні
     */
    fun usageToday(
        context: Context,
        packages: Set<String>,
        foregroundPkg: String?
    ): Map<String, Long> {
        val result = HashMap<String, Long>()
        if (packages.isEmpty()) return result

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfToday()
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(start, now)
        } catch (e: SecurityException) {
            null
        } ?: return result

        val openSince = HashMap<String, Long>()
        val seen = HashSet<String>()
        val event = UsageEvents.Event()

        fun close(pkg: String, at: Long) {
            val since = openSince.remove(pkg) ?: return
            result[pkg] = (result[pkg] ?: 0L) + (at - since)
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            val ts = event.timeStamp
            when (event.eventType) {
                ACTIVITY_RESUMED -> {
                    // На екрані може бути лише один додаток: решту закриваємо.
                    for (other in openSince.keys.toList()) if (other != pkg) close(other, ts)
                    if (pkg in packages && !openSince.containsKey(pkg)) openSince[pkg] = ts
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
                }
            }
        }

        if (foregroundPkg != null) {
            val since = openSince[foregroundPkg]
            if (since != null) {
                result[foregroundPkg] = (result[foregroundPkg] ?: 0L) + (now - since)
            }
        }
        return result
    }
}
