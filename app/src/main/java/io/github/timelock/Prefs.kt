package io.github.timelock

import android.content.Context
import java.time.LocalDate

/**
 * Налаштування лімітів.
 *
 * Головне правило: посилити обмеження можна одразу, а послабити
 * (збільшити ліміт, прибрати додаток, вимкнути захист) — лише з наступного дня.
 * Так у момент спокуси не вийде просто зайти й зняти блок.
 */
class Prefs(context: Context) {

    enum class Result { APPLIED, TOMORROW, UNCHANGED }

    private val sp = context.applicationContext
        .getSharedPreferences("timelock", Context.MODE_PRIVATE)

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

    fun protectionOffPending(): Boolean {
        applyPendingIfDue()
        return sp.getBoolean(KEY_PROTECTION_OFF_PENDING, false)
    }

    @Synchronized
    fun setProtection(on: Boolean): Result {
        applyPendingIfDue()
        val current = sp.getBoolean(KEY_PROTECTION, false)
        if (on) {
            val wasPendingOff = sp.getBoolean(KEY_PROTECTION_OFF_PENDING, false)
            sp.edit()
                .putBoolean(KEY_PROTECTION, true)
                .remove(KEY_PROTECTION_OFF_PENDING)
                .apply()
            return if (current && !wasPendingOff) Result.UNCHANGED else Result.APPLIED
        }
        if (!current) return Result.UNCHANGED
        sp.edit()
            .putBoolean(KEY_PROTECTION_OFF_PENDING, true)
            .putLong(KEY_PENDING_DAY, today() + 1)
            .apply()
        return Result.TOMORROW
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
        val day = sp.getLong(KEY_PENDING_DAY, Long.MAX_VALUE)
        if (today() < day) return

        val active = decode(sp.getString(KEY_ACTIVE, null))
        val pending = decode(sp.getString(KEY_PENDING, null))
        for ((pkg, min) in pending) {
            if (min <= 0) active.remove(pkg) else active[pkg] = min
        }
        val e = sp.edit()
            .putString(KEY_ACTIVE, encode(active))
            .remove(KEY_PENDING)
            .remove(KEY_PENDING_DAY)
        if (sp.getBoolean(KEY_PROTECTION_OFF_PENDING, false)) {
            e.putBoolean(KEY_PROTECTION, false).remove(KEY_PROTECTION_OFF_PENDING)
        }
        e.apply()
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
        private const val KEY_PROTECTION_OFF_PENDING = "protection_off_pending"
    }
}
