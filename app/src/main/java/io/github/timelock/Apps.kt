package io.github.timelock

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** Додаток, який можна обмежити. */
data class AppItem(val pkg: String, val label: String, val icon: Drawable)

object Apps {
    /** Усі додатки з іконкою на робочому столі, крім самого LockTime. Повільно — викликати не в UI-потоці. */
    fun loadLaunchable(context: Context): List<AppItem> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val packages = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
        return packages.mapNotNull { pkg ->
            try {
                @Suppress("DEPRECATION")
                val info = pm.getApplicationInfo(pkg, 0)
                AppItem(pkg, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info))
            } catch (e: Exception) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }
}
