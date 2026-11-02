package io.github.timelock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Запасний екран блокування — на випадок, якщо накладку служби показати не вдалося.
 * Вигляд спільний з накладкою (BlockView).
 */
class BlockActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Lang.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            BlockView.build(
                this,
                intent.getStringExtra(EXTRA_PACKAGE),
                intent.getStringExtra(EXTRA_REASON),
                intent.getBooleanExtra(EXTRA_BROWSER, false)
            ) { goHome() }
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_REASON = "reason"
        const val EXTRA_BROWSER = "browser"
    }
}
