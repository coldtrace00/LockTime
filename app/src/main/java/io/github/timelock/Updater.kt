package io.github.timelock

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Перевірка й встановлення оновлень з GitHub.
 *
 * Мережа використовується ЛИШЕ тут і ЛИШЕ коли користувач явно дозволив
 * (Consent.YES). Запит один: номер останньої версії з api.github.com.
 * Жодних даних про користувача, його додатки чи налаштування не надсилається.
 */
object Updater {

    enum class Consent { UNASKED, YES, NO }

    data class Release(val version: String, val apkUrl: String?, val pageUrl: String)

    private const val REPO = "coldtrace00/LockTime"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val PAGE_URL = "https://github.com/$REPO/releases/latest"
    const val SOURCE_URL = "https://github.com/$REPO"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private const val KEY_CONSENT = "update_consent"
    private const val KEY_LAST_CHECK_DAY = "update_last_check_day"
    private const val KEY_LATEST = "update_latest_version"
    private const val KEY_INSTALL_UNTIL = "update_install_until"

    private fun sp(c: Context) = c.getSharedPreferences("timelock", Context.MODE_PRIVATE)

    fun consent(c: Context): Consent =
        runCatching { Consent.valueOf(sp(c).getString(KEY_CONSENT, null) ?: "UNASKED") }
            .getOrDefault(Consent.UNASKED)

    fun setConsent(c: Context, value: Consent) {
        sp(c).edit().putString(KEY_CONSENT, value.name).apply()
    }

    fun installedVersion(c: Context): String =
        try {
            c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }

    /** Остання відома версія з попередньої перевірки (без мережі). */
    fun knownLatest(c: Context): String? = sp(c).getString(KEY_LATEST, null)

    /** Автоматична перевірка — не частіше разу на день і лише за згоди. */
    fun shouldAutoCheck(c: Context): Boolean =
        consent(c) == Consent.YES && sp(c).getLong(KEY_LAST_CHECK_DAY, -1L) != LocalDate.now().toEpochDay()

    /** Мережевий запит. Викликати лише не в UI-потоці і лише за згоди. */
    fun fetchLatest(c: Context): Release {
        check(consent(c) == Consent.YES) { "Update checks are not allowed" }
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "LockTime")
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").trimStart('v', 'V')
            var apk: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a.optString("browser_download_url")
                        break
                    }
                }
            }
            sp(c).edit()
                .putLong(KEY_LAST_CHECK_DAY, LocalDate.now().toEpochDay())
                .putString(KEY_LATEST, version)
                .apply()
            return Release(version, apk, json.optString("html_url", PAGE_URL))
        } finally {
            conn.disconnect()
        }
    }

    /** "1.0.20" новіша за "1.0.18"? Порівнюємо числа по частинах. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Чи дозволив користувач LockTime встановлювати APK (Android 8+). */
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    /** Ставить завантаження в системний DownloadManager; повертає його id. */
    fun download(c: Context, release: Release): Long {
        val url = release.apkUrl ?: error("No APK in release")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("LockTime ${release.version}")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, "LockTime-${release.version}.apk")
        val dm = c.getSystemService(DownloadManager::class.java)
        return dm.enqueue(request)
    }

    /** null — ще завантажується; true — готово; false — помилка. */
    fun downloadStatus(c: Context, id: Long): Boolean? {
        val dm = c.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return false
            return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> true
                DownloadManager.STATUS_FAILED -> false
                else -> null
            }
        }
    }

    /** Відкриває системне вікно встановлення завантаженого APK. */
    fun install(c: Context, id: Long): Boolean {
        val dm = c.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(id) ?: return false
        // На 3 хвилини дозволяємо інсталятору показати LockTime, щоб захист від
        // видалення не закрив вікно оновлення (видалення при цьому все одно блокується).
        sp(c).edit().putLong(KEY_INSTALL_UNTIL, System.currentTimeMillis() + 3 * 60_000L).apply()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun installWindowOpen(c: Context): Boolean =
        System.currentTimeMillis() < sp(c).getLong(KEY_INSTALL_UNTIL, 0L)
}
