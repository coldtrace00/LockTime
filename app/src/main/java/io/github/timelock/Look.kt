package io.github.timelock

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Вигляд: тема (авто / світла / темна) і кольори, що підходять до неї.
 * «Авто» повторює тему телефона.
 */
object Look {
    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"
    private const val KEY = "theme"

    fun get(context: Context): String =
        context.getSharedPreferences("timelock", Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    fun set(context: Context, theme: String) {
        context.getSharedPreferences("timelock", Context.MODE_PRIVATE).edit().putString(KEY, theme).apply()
    }

    fun isDark(context: Context): Boolean = when (get(context)) {
        DARK -> true
        LIGHT -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** Тему треба встановити до super.onCreate() активності. */
    fun themeRes(context: Context): Int =
        if (isDark(context)) android.R.style.Theme_Material
        else android.R.style.Theme_Material_Light_DarkActionBar

    // Кольори. Акцент для заливки однаковий, для тексту в темній темі — світліший.
    const val ACCENT_FILL = 0xFF3B5BDB.toInt()

    fun accentText(c: Context) = if (isDark(c)) 0xFF91A7FF.toInt() else ACCENT_FILL
    fun danger(c: Context) = if (isDark(c)) 0xFFFF8787.toInt() else 0xFFC92A2A.toInt()
    fun text(c: Context) = if (isDark(c)) 0xFFECECEC.toInt() else 0xFF1A1A1A.toInt()
    fun muted(c: Context) = if (isDark(c)) 0xFF9E9EA6.toInt() else 0xFF6B6B73.toInt()
    fun card(c: Context) = if (isDark(c)) 0xFF1E1E24.toInt() else 0xFFF2F3F8.toInt()
    fun chip(c: Context) = if (isDark(c)) 0xFF2E2E36.toInt() else 0xFFE4E7F2.toInt()
    fun barEmpty(c: Context) = if (isDark(c)) 0xFF2E2E36.toInt() else 0xFFE4E7F2.toInt()

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    const val WHITE = Color.WHITE
}
