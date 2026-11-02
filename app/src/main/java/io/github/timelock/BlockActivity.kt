package io.github.timelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Екран блокування. Пароля немає — лише кнопка на головний екран.
 * Спокійний вигляд: приглушена іконка самого додатка, заголовок, пояснення.
 */
class BlockActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val label = pkg?.let { appLabel(it) } ?: getString(R.string.this_app)
        val reason = intent.getStringExtra(EXTRA_REASON)
        val viaBrowser = intent.getBooleanExtra(EXTRA_BROWSER, false)
        val prefs = Prefs(this)
        val rule = pkg?.let { prefs.rules()[it] }

        val (title, body) = when (reason) {
            LimitService.Reason.NIGHT.name -> {
                val w = prefs.nightWindow()
                getString(R.string.block_night_title) to getString(
                    R.string.block_night_body, label,
                    MainActivity.formatTime(w.start), MainActivity.formatTime(w.end)
                )
            }
            LimitService.Reason.OPENS.name -> {
                val n = rule?.opens
                val limitText = if (n != null) " ($n)" else ""
                getString(R.string.block_opens_title) to getString(R.string.block_opens_body, label, limitText)
            }
            else -> {
                val minutes = rule?.minutesFor(java.time.LocalDate.now())
                val limitText = if (minutes != null) " (${MainActivity.formatMinutes(this, minutes)})" else ""
                getString(R.string.block_time_title) to getString(R.string.block_time_body, label, limitText)
            }
        }
        val fullBody = if (viaBrowser) "$body\n\n" + getString(R.string.block_site_note) else body

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val dark = Look.isDark(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(if (dark) 0xFF16181D.toInt() else 0xFFF4F5F8.toInt())
            setPadding(dp(36), dp(36), dp(36), dp(36))
        }

        // Іконка заблокованого додатка — знебарвлена й приглушена.
        pkg?.let { appIcon(it) }?.let { drawable ->
            root.addView(ImageView(this).apply {
                setImageDrawable(drawable.mutate())
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                imageAlpha = 140
            }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(24) })
        }

        root.addView(TextView(this).apply {
            text = title
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Look.text(this@BlockActivity))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })

        root.addView(TextView(this).apply {
            text = fullBody
            textSize = 16f
            setLineSpacing(0f, 1.15f)
            setTextColor(Look.muted(this@BlockActivity))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(36))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.btn_got_it)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Look.WHITE)
            background = Look.rounded(Look.ACCENT_FILL, 24 * density)
            setPadding(dp(40), dp(12), dp(40), dp(12))
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
        finish()
    }

    private fun appLabel(pkg: String): String? =
        try {
            @Suppress("DEPRECATION")
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }

    private fun appIcon(pkg: String): Drawable? =
        try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            null
        }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REASON = "reason"
        const val EXTRA_BROWSER = "browser"
    }
}
