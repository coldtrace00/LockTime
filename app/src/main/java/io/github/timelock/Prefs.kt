package io.github.timelock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.LocalDate

/**
 * Налаштування лімітів.
 *
 * Головне правило: посилити обмеження можна одразу, а послабити
 * (збільшити ліміт, прибрати додаток) — лише з наступного дня.
 * Захист від видалення знімається лише через 72 години після запиту.
 * Так у момент спокуси не вийде просто зайти й зняти блок.
 */
class Prefs(context: Context) {

    enum class Result { APPLIED, TOMORROW, UNCHANGED }

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("timelock", Context.MODE_PRIVATE)

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Активні ліміти: пакет -> хвилин на день. */
    fun activeLimits(): Map<String, Int> {
        applyPendingIfDue()
        return decode(sp.getString(KEY_ACTIVE, null))
    }

    /** Відкладені послаблення: пакет -> нові хвилини (0 = прибрати ліміт). */
    fun pendingLimits(): Map<String, Int> {
        applyPendingIfDue()
        return decode(sp.getString(KEY_PENDING, null))
    }

    @Synchronized
    fun setLimit(pkg: String, minutes: Int): Result {
        applyPendingIfDue()
        val active = decode(sp.getString(KEY_ACTIVE, null))
        val pending = decode(sp.getString(KEY_PENDING, null))
        val current = active[pkg]
        val newMin = minutes.coerceIn(0, MAX_MINUTES)

        val tighter = newMin > 0 && (current == null || newMin < current)
        val looser = current != null && (newMin == 0 || newMin > current)

        return when {
            tighter -> {
                active[pkg] = newMin
                pending.remove(pkg)
                save(active, pending, schedule = false)
                Result.APPLIED
            }
            looser -> {
                pending[pkg] = newMin
                save(active, pending, schedule = true)
                Result.TOMORROW
            }
            else -> {
                // Той самий ліміт — скасовуємо відкладене послаблення, якщо воно було.
                if (pending.remove(pkg) != null) save(active, pending, schedule = false)
                Result.UNCHANGED
            }
        }
    }

    fun protectionEnabled(): Boolean {
        applyPendingIfDue()
        return sp.getBoolean(KEY_PROTECTION, false)
    }

    /**
     * Орієнтовний момент зняття захисту (мілісекунди), або null, якщо зняття не заплановано.
     * Орієнтовний, бо час, коли телефон вимкнений, не зараховується.
     */
    fun protectionOffAt(): Long? {
        applyPendingIfDue()
        val remaining = sp.getLong(KEY_OFF_REMAINING, -1L)
        return if (remaining >= 0L) System.currentTimeMillis() + remaining else null
    }

    /**
     * Увімкнути захист — одразу (і скасувати заплановане зняття).
     * Вимкнути — лише через [PROTECTION_OFF_DELAY_MS]; повертає орієнтовний час зняття.
     */
    @Synchronized
    fun setProtection(on: Boolean): Long? {
        applyPendingIfDue()
        if (on) {
            sp.edit()
                .putBoolean(KEY_PROTECTION, true)
                .remove(KEY_OFF_REMAINING)
                .remove(KEY_OFF_LAST_ELAPSED)
                .remove(KEY_OFF_LAST_BOOT)
                .apply()
            return null
        }
        if (!sp.getBoolean(KEY_PROTECTION, false)) return null
        val remaining = sp.getLong(KEY_OFF_REMAINING, -1L)
        if (remaining >= 0L) {
            // Повторне натискання не скорочує очікування.
            return System.currentTimeMillis() + remaining
        }
        sp.edit()
            .putLong(KEY_OFF_REMAINING, PROTECTION_OFF_DELAY_MS)
            .putLong(KEY_OFF_LAST_ELAPSED, SystemClock.elapsedRealtime())
            .putInt(KEY_OFF_LAST_BOOT, bootCount())
            .apply()
        return System.currentTimeMillis() + PROTECTION_OFF_DELAY_MS
    }

    /**
     * Відлік зняття захисту йде за «системним секундоміром» (elapsedRealtime),
     * а не за годинником телефона. Тому переведення дати чи часу в налаштуваннях
     * нічого не дає: секундомір не можна підкрутити.
     * Секундомір обнуляється при перезавантаженні — тоді зараховуємо лише час
     * від увімкнення, а період, коли телефон був вимкнений, втрачається.
     */
    private fun tickProtectionCountdown() {
        var remaining = sp.getLong(KEY_OFF_REMAINING, -1L)
        if (remaining < 0L) return
        val nowElapsed = SystemClock.elapsedRealtime()
        val boot = bootCount()
        val lastElapsed = sp.getLong(KEY_OFF_LAST_ELAPSED, nowElapsed)
        val lastBoot = sp.getInt(KEY_OFF_LAST_BOOT, boot)
        val sameBoot = boot == lastBoot && nowElapsed >= lastElapsed
        val passed = if (sameBoot) nowElapsed - lastElapsed else nowElapsed
        remaining -= passed
        if (remaining <= 0L) {
            sp.edit()
                .putBoolean(KEY_PROTECTION, false)
                .remove(KEY_OFF_REMAINING)
                .remove(KEY_OFF_LAST_ELAPSED)
                .remove(KEY_OFF_LAST_BOOT)
                .apply()
        } else {
            sp.edit()
                .putLong(KEY_OFF_REMAINING, remaining)
                .putLong(KEY_OFF_LAST_ELAPSED, nowElapsed)
                .putInt(KEY_OFF_LAST_BOOT, boot)
                .apply()
        }
    }

    private fun bootCount(): Int =
        try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }

    private fun save(active: Map<String, Int>, pending: Map<String, Int>, schedule: Boolean) {
        val e = sp.edit()
            .putString(KEY_ACTIVE, encode(active))
            .putString(KEY_PENDING, encode(pending))
        if (schedule) e.putLong(KEY_PENDING_DAY, today() + 1)
        e.apply()
    }

    @Synchronized
    private fun applyPendingIfDue() {
        tickProtectionCountdown()

        val day = sp.getLong(KEY_PENDING_DAY, Long.MAX_VALUE)
        if (today() < day) return

        val active = decode(sp.getString(KEY_ACTIVE, null))
        val pending = decode(sp.getString(KEY_PENDING, null))
        for ((pkg, min) in pending) {
            if (min <= 0) active.remove(pkg) else active[pkg] = min
        }
        sp.edit()
            .putString(KEY_ACTIVE, encode(active))
            .remove(KEY_PENDING)
            .remove(KEY_PENDING_DAY)
            .apply()
    }

    private fun decode(s: String?): MutableMap<String, Int> {
        val map = mutableMapOf<String, Int>()
        if (s.isNullOrEmpty()) return map
        for (part in s.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val value = part.substring(i + 1).toIntOrNull() ?: continue
            map[part.substring(0, i)] = value
        }
        return map
    }

    private fun encode(map: Map<String, Int>): String =
        map.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        const val MAX_MINUTES = 24 * 60
        private const val KEY_ACTIVE = "active_limits"
        private const val KEY_PENDING = "pending_limits"
        private const val KEY_PENDING_DAY = "pending_day"
        private const val KEY_PROTECTION = "protection"
        private const val KEY_OFF_REMAINING = "protection_off_remaining"
        private const val KEY_OFF_LAST_ELAPSED = "protection_off_last_elapsed"
        private const val KEY_OFF_LAST_BOOT = "protection_off_last_boot"
        const val PROTECTION_OFF_DELAY_MS = 72L * 60 * 60 * 1000
    }
}
