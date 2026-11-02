package io.github.timelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Екран «Час вийшов». Пароля немає — лише кнопка на головний екран. */
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

        val (icon, title, body) = when (reason) {
            LimitService.Reason.NIGHT.name -> {
                val w = prefs.nightWindow()
                Triple(
                    "🌙",
                    getString(R.string.block_night_title),
                    getString(
                        R.string.block_night_body, label,
                        MainActivity.formatTime(w.start), MainActivity.formatTime(w.end)
                    )
                )
            }
            LimitService.Reason.OPENS.name -> {
                val n = rule?.opens
                val limitText = if (n != null) " ($n)" else ""
                Triple(
                    "🚪",
                    getString(R.string.block_opens_title),
                    getString(R.string.block_opens_body, label, limitText)
                )
            }
            else -> {
                val minutes = rule?.minutesFor(java.time.LocalDate.now())
                val limitText = if (minutes != null) " (${MainActivity.formatMinutes(this, minutes)})" else ""
                Triple(
                    "⏳",
                    getString(R.string.block_time_title),
                    getString(R.string.block_time_body, label, limitText)
                )
            }
        }
        val fullBody = if (viaBrowser) "$body\n\n" + getString(R.string.block_site_note) else body

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1E2A4A"))
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }

        root.addView(TextView(this).apply {
            text = icon
            textSize = 64f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = title
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = fullBody
            textSize = 17f
            setTextColor(Color.parseColor("#C9D3EE"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32))
        })

        root.addView(Button(this).apply {
            text = getString(R.string.btn_got_it)
            setOnClickListener { goHome() }
        })

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

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REASON = "reason"
        const val EXTRA_BROWSER = "browser"
    }
}
