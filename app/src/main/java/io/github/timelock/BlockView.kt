package io.github.timelock

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Вигляд екрана блокування: приглушена іконка додатка, причина, кнопка «Зрозуміло».
 * Використовується і як накладка служби (основний спосіб), і в BlockActivity (запасний).
 */
object BlockView {

    /** @param context контекст з обраною мовою (Lang.wrap) */
    fun build(
        context: Context,
        pkg: String?,
        reason: String?,
        viaBrowser: Boolean,
        onClose: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pm = context.packageManager
        val label = pkg?.let {
            try {
                @Suppress("DEPRECATION")
                pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
            } catch (e: Exception) {
                null
            }
        } ?: context.getString(R.string.this_app)
        val icon = pkg?.let {
            try {
                pm.getApplicationIcon(it)
            } catch (e: Exception) {
                null
            }
        }

        val prefs = Prefs(context)
        val rule = pkg?.let { prefs.rules()[it] }
        val (title, body) = when (reason) {
            LimitService.Reason.NIGHT.name -> {
                val w = prefs.nightWindow()
                context.getString(R.string.block_night_title) to context.getString(
                    R.string.block_night_body, label,
                    MainActivity.formatTime(w.start), MainActivity.formatTime(w.end)
                )
            }
            LimitService.Reason.OPENS.name -> {
                val n = rule?.opens
                val limitText = if (n != null) " ($n)" else ""
                context.getString(R.string.block_opens_title) to
                    context.getString(R.string.block_opens_body, label, limitText)
            }
            else -> {
                val minutes = rule?.minutesFor(java.time.LocalDate.now())
                val limitText = if (minutes != null) " (${MainActivity.formatMinutes(context, minutes)})" else ""
                context.getString(R.string.block_time_title) to
                    context.getString(R.string.block_time_body, label, limitText)
            }
        }
        val fullBody = if (viaBrowser) "$body\n\n" + context.getString(R.string.block_site_note) else body

        val dark = Look.isDark(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (dark) 0xFF16181D.toInt() else 0xFFF4F5F8.toInt())
            setPadding(dp(36), dp(36), dp(36), dp(36))
            isClickable = true // дотики не проходять до того, що під накладкою
        }

        if (icon != null) {
            root.addView(ImageView(context).apply {
                setImageDrawable(icon.mutate())
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                imageAlpha = 140
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(24) })
        }

        root.addView(TextView(context).apply {
            text = title
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text(context))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(TextView(context).apply {
            text = fullBody
            textSize = 16f
            setLineSpacing(0f, 1.15f)
            setTextColor(Look.muted(context))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(36))
        })

        root.addView(TextView(context).apply {
            text = context.getString(R.string.btn_got_it)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Look.WHITE)
            background = Look.rounded(Look.ACCENT_FILL, 24 * density)
            setPadding(dp(40), dp(12), dp(40), dp(12))
            setOnClickListener { onClose() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        return root
    }
}
