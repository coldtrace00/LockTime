package io.github.timelock

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Мова інтерфейсу. За замовчуванням — українська, незалежно від мови телефона;
 * англійську можна обрати в додатку.
 */
object Lang {
    const val UK = "uk"
    const val EN = "en"
    private const val KEY = "language"

    fun get(context: Context): String =
        context.getSharedPreferences("timelock", Context.MODE_PRIVATE).getString(KEY, UK) ?: UK

    fun set(context: Context, lang: String) {
        context.getSharedPreferences("timelock", Context.MODE_PRIVATE).edit().putString(KEY, lang).apply()
    }

    /** Контекст, у якому всі getString() повертають тексти обраною мовою. */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(get(base)))
        return base.createConfigurationContext(config)
    }
}
