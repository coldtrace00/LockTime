package io.github.timelock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Після оновлення LockTime деякі телефони (зокрема Samsung) вимикають його службу
 * спеціальних можливостей. Заборонити це не можна, тому хоча б нагадуємо.
 */
class UpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        // Даємо системі кілька секунд: інколи служба вмикається назад сама.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (!serviceEnabled(app)) notifyServiceOff(app)
            } finally {
                pending.finish()
            }
        }, 6_000L)
    }

    private fun serviceEnabled(c: Context): Boolean {
        val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val mine = ComponentName(c, LimitService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    private fun notifyServiceOff(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        val l = Lang.wrap(c)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, l.getString(R.string.service_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            c, 1, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(c, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(l.getString(R.string.service_off_title))
            .setContentText(l.getString(R.string.service_off_text))
            .setStyle(Notification.BigTextStyle().bigText(l.getString(R.string.service_off_text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, n)
        } catch (e: SecurityException) {
            // сповіщення заборонені
        }
    }

    companion object {
        private const val CHANNEL_ID = "service_state"
        private const val NOTIFICATION_ID = 7002
    }
}
