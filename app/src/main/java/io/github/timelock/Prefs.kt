package io.github.timelock

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Правило для одного додатка. null у числовому полі означає «без обмеження».
 *
 * @param weekday хвилин на день у будні
 * @param weekend хвилин на день у суботу й неділю; null — як у будні
 * @param opens скільки разів на день можна відкрити
 * @param night блокувати в нічний режим
 */
data class Rule(
    val weekday: Int? = null,
    val weekend: Int? = null,
    val opens: Int? = null,
    val night: Boolean = false
) {
    val isEmpty: Boolean
        get() = weekday == null && weekend == null && opens == null && !night

    /** Ліміт у вихідні з урахуванням «як у будні». */
    val effectiveWeekend: Int?
        get() = weekend ?: weekday

    fun minutesFor(date: LocalDate): Int? {
        val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return if (isWeekend) effectiveWeekend else weekday
    }

    /** Прибирає дублювання: якщо вихідні = будні, зберігаємо лише будні. */
    fun normalized(): Rule {
        val wd = weekday?.takeIf { it > 0 }
        val we = weekend?.takeIf { it > 0 }
        return copy(
            weekday = wd,
            weekend = if (we == wd) null else we,
            opens = opens?.takeIf { it > 0 }
        )
    }

    companion object {
        val EMPTY = Rule()

        private fun stricter(a: Int?, b: Int?): Int? =
            if (a == null) b else if (b == null) a else minOf(a, b)

        /** Найсуворіше з двох правил — кожне поле окремо. */
        fun strictest(a: Rule, b: Rule): Rule = Rule(
            weekday = stricter(a.weekday, b.weekday),
            weekend = stricter(a.effectiveWeekend, b.effectiveWeekend),
            opens = stricter(a.opens, b.opens),
            night = a.night || b.night
        ).normalized()
    }
}

/** Нічне вікно у хвилинах від опівночі; може переходити через північ (23:00–07:00). */
data class NightWindow(val start: Int, val end: Int) {

    fun covers(minuteOfDay: Int): Boolean = when {
        start == end -> false
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    fun coversNow(): Boolean {
        val t = LocalTime.now()
        return covers(t.hour * 60 + t.minute)
    }

    /** Чи покриває це вікно всі хвилини вікна [other] (тобто не м'якше за нього). */
    fun contains(other: NightWindow): Boolean =
        (0 until 24 * 60).all { !other.covers(it) || covers(it) }

    companion object {
        val DEFAULT = NightWindow(23 * 60, 7 * 60)
    }
}

/**
 * Збереження налаштувань.
 *
 * Головне правило: посилити обмеження можна одразу, а послабити — лише з
 * наступного дня. Якщо зміна частково суворіша, частково м'якша, суворіша
 * частина діє одразу, а повне нове правило — з завтра.
 * Захист від видалення знімається лише через 72 години після запиту.
 */
class Prefs(context: Context) {

    enum class Result { APPLIED, PARTLY, TOMORROW, UNCHANGED }

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences("timelock", Context.MODE_PRIVATE)

    private fun today(): Long = LocalDate.now().toEpochDay()

    // ---------- Правила додатків ----------

    fun rules(): Map<String, Rule> {
        applyPendingIfDue()
        return decodeRules(sp.getString(KEY_RULES, null))
    }

    fun pendingRules(): Map<String, Rule> {
        applyPendingIfDue()
        return decodeRules(sp.getString(KEY_RULES_PENDING, null))
    }

    @Synchronized
    fun setRule(pkg: String, requested: Rule): Result {
        applyPendingIfDue()
        val active = decodeRules(sp.getString(KEY_RULES, null))
        val pending = decodeRules(sp.getString(KEY_RULES_PENDING, null))
        val current = active[pkg] ?: Rule.EMPTY
        val wanted = requested.normalized()

        if (wanted == current) {
            // Повернулися до чинного правила — скасовуємо відкладені зміни.
            if (pending.remove(pkg) != null) saveRules(active, pending, schedule = false)
            return Result.UNCHANGED
        }

        val now = Rule.strictest(current, wanted)
        if (now.isEmpty) active.remove(pkg) else active[pkg] = now

        return if (now == wanted) {
            pending.remove(pkg)
            saveRules(active, pending, schedule = false)
            Result.APPLIED
        } else {
            pending[pkg] = wanted
            saveRules(active, pending, schedule = true)
            if (now == current) Result.TOMORROW else Result.PARTLY
        }
    }

    private fun saveRules(active: Map<String, Rule>, pending: Map<String, Rule>, schedule: Boolean) {
        val e = sp.edit()
            .putString(KEY_RULES, encodeRules(active))
            .putString(KEY_RULES_PENDING, encodeRules(pending))
        if (schedule) e.putLong(KEY_PENDING_DAY, today() + 1)
        e.apply()
    }

    // ---------- Нічний режим ----------

    fun nightWindow(): NightWindow {
        applyPendingIfDue()
        return NightWindow(
            sp.getInt(KEY_NIGHT_START, NightWindow.DEFAULT.start),
            sp.getInt(KEY_NIGHT_END, NightWindow.DEFAULT.end)
        )
    }

    fun pendingNightWindow(): NightWindow? {
        applyPendingIfDue()
        val s = sp.getInt(KEY_NIGHT_PENDING_START, -1)
        val e = sp.getInt(KEY_NIGHT_PENDING_END, -1)
        return if (s >= 0 && e >= 0) NightWindow(s, e) else null
    }

    /** Ширше вікно — одразу; вужче чи зсунуте — з завтра. */
    @Synchronized
    fun setNightWindow(window: NightWindow): Result {
        val current = nightWindow()
        if (window == current) {
            sp.edit().remove(KEY_NIGHT_PENDING_START).remove(KEY_NIGHT_PENDING_END).apply()
            return Result.UNCHANGED
        }
        if (window.contains(current)) {
            sp.edit()
                .putInt(KEY_NIGHT_START, window.start)
                .putInt(KEY_NIGHT_END, window.end)
                .remove(KEY_NIGHT_PENDING_START)
                .remove(KEY_NIGHT_PENDING_END)
                .apply()
            return Result.APPLIED
        }
        sp.edit()
            .putInt(KEY_NIGHT_PENDING_START, window.start)
            .putInt(KEY_NIGHT_PENDING_END, window.end)
            .putLong(KEY_PENDING_DAY, today() + 1)
            .apply()
        return Result.TOMORROW
    }

    // ---------- Захист від видалення ----------

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

    // ---------- Застосування відкладених змін ----------

    @Synchronized
    private fun applyPendingIfDue() {
        tickProtectionCountdown()
        migrateOldLimits()

        val day = sp.getLong(KEY_PENDING_DAY, Long.MAX_VALUE)
        if (today() < day) return

        val active = decodeRules(sp.getString(KEY_RULES, null))
        val pending = decodeRules(sp.getString(KEY_RULES_PENDING, null))
        for ((pkg, rule) in pending) {
            if (rule.isEmpty) active.remove(pkg) else active[pkg] = rule
        }
        val e = sp.edit()
            .putString(KEY_RULES, encodeRules(active))
            .remove(KEY_RULES_PENDING)
            .remove(KEY_PENDING_DAY)
        val ns = sp.getInt(KEY_NIGHT_PENDING_START, -1)
        val ne = sp.getInt(KEY_NIGHT_PENDING_END, -1)
        if (ns >= 0 && ne >= 0) {
            e.putInt(KEY_NIGHT_START, ns).putInt(KEY_NIGHT_END, ne)
        }
        e.remove(KEY_NIGHT_PENDING_START).remove(KEY_NIGHT_PENDING_END)
        e.apply()
    }

    /** Версії до 1.1 зберігали лише «пакет=хвилини». */
    private fun migrateOldLimits() {
        val old = sp.getString(KEY_OLD_ACTIVE, null) ?: return
        val rules = decodeRules(sp.getString(KEY_RULES, null))
        for (part in old.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val minutes = part.substring(i + 1).toIntOrNull() ?: continue
            val pkg = part.substring(0, i)
            if (minutes > 0 && pkg !in rules) rules[pkg] = Rule(weekday = minutes)
        }
        sp.edit()
            .putString(KEY_RULES, encodeRules(rules))
            .remove(KEY_OLD_ACTIVE)
            .remove(KEY_OLD_PENDING)
            .apply()
    }

    // ---------- Формат зберігання: "пакет=будні,вихідні,відкриття,ніч;..." ----------

    private fun decodeRules(s: String?): MutableMap<String, Rule> {
        val map = mutableMapOf<String, Rule>()
        if (s.isNullOrEmpty()) return map
        for (part in s.split(";")) {
            val i = part.lastIndexOf('=')
            if (i <= 0) continue
            val f = part.substring(i + 1).split(",")
            if (f.size < 4) continue
            map[part.substring(0, i)] = Rule(num(f[0]), num(f[1]), num(f[2]), f[3] == "1")
        }
        return map
    }

    private fun num(x: String): Int? = x.toIntOrNull()?.takeIf { it > 0 }

    private fun encodeRules(map: Map<String, Rule>): String =
        map.entries.joinToString(";") { (pkg, r) ->
            "$pkg=${r.weekday ?: -1},${r.weekend ?: -1},${r.opens ?: -1},${if (r.night) 1 else 0}"
        }

    companion object {
        const val MAX_MINUTES = 24 * 60
        const val PROTECTION_OFF_DELAY_MS = 72L * 60 * 60 * 1000

        private const val KEY_RULES = "rules"
        private const val KEY_RULES_PENDING = "rules_pending"
        private const val KEY_PENDING_DAY = "pending_day"
        private const val KEY_NIGHT_START = "night_start"
        private const val KEY_NIGHT_END = "night_end"
        private const val KEY_NIGHT_PENDING_START = "night_pending_start"
        private const val KEY_NIGHT_PENDING_END = "night_pending_end"
        private const val KEY_PROTECTION = "protection"
        private const val KEY_OFF_REMAINING = "protection_off_remaining"
        private const val KEY_OFF_LAST_ELAPSED = "protection_off_last_elapsed"
        private const val KEY_OFF_LAST_BOOT = "protection_off_last_boot"
        private const val KEY_OLD_ACTIVE = "active_limits"
        private const val KEY_OLD_PENDING = "pending_limits"
    }
}
