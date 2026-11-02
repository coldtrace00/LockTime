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

    // Спокійна палітра: приглушений індиго, м'які статусні кольори.
    const val ACCENT_FILL = 0xFF4A5FC1.toInt()

    fun accentText(c: Context) = if (isDark(c)) 0xFF9AA8F0.toInt() else ACCENT_FILL
    fun danger(c: Context) = if (isDark(c)) 0xFFE8867E.toInt() else 0xFFB5473F.toInt()
    fun ok(c: Context) = if (isDark(c)) 0xFF7CC49A.toInt() else 0xFF3F8F5F.toInt()
    fun warn(c: Context) = if (isDark(c)) 0xFFE0B070.toInt() else 0xFFB7792A.toInt()
    fun text(c: Context) = if (isDark(c)) 0xFFE6E7EB.toInt() else 0xFF22252B.toInt()
    fun muted(c: Context) = if (isDark(c)) 0xFF9A9EA9.toInt() else 0xFF6E7380.toInt()
    fun card(c: Context) = if (isDark(c)) 0xFF1C1E24.toInt() else 0xFFF4F5F8.toInt()
    fun chip(c: Context) = if (isDark(c)) 0xFF2A2D35.toInt() else 0xFFE6E9F0.toInt()
    fun barEmpty(c: Context) = if (isDark(c)) 0xFF2A2D35.toInt() else 0xFFE6E9F0.toInt()

    fun bg(c: Context) = if (isDark(c)) 0xFF121316.toInt() else 0xFFFAFAFC.toInt()
    fun navBg(c: Context) = if (isDark(c)) 0xFF1A1C21.toInt() else 0xFFFFFFFF.toInt()
    fun divider(c: Context) = if (isDark(c)) 0xFF262931.toInt() else 0xFFECEEF3.toInt()
    fun accentSoft(c: Context) = if (isDark(c)) 0xFF2B3150.toInt() else 0xFFE3E7F8.toInt()

    /** Тема без верхньої панелі — для головного екрана з власними заголовками. */
    fun themeResNoBar(context: Context): Int =
        if (isDark(context)) android.R.style.Theme_Material_NoActionBar
        else android.R.style.Theme_Material_Light_NoActionBar

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
    }

    const val WHITE = Color.WHITE
}
