package com.eza.hyperglow.update

import android.content.Context
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Remembers the last answer so the banner survives a cold start with no network, and so the host
 * is polled at most once an interval rather than on every screen open.
 */
internal object UpdateCheckStore {
    internal const val PREFS = "update_check"

    fun read(context: Context): LatestVersion? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val versionCode = prefs.getLong(KEY_VERSION_CODE, 0L)
        val versionName = prefs.getString(KEY_VERSION_NAME, null)
        if (versionCode <= 0L || versionName.isNullOrBlank()) return null
        return LatestVersion(versionCode = versionCode, versionName = versionName)
    }

    fun save(context: Context, latest: LatestVersion, nowMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_VERSION_CODE, latest.versionCode)
            .putString(KEY_VERSION_NAME, latest.versionName)
            .putLong(KEY_CHECKED_AT, nowMillis)
            .apply()
    }

    fun markChecked(context: Context, nowMillis: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_CHECKED_AT, nowMillis)
            .apply()
    }

    fun lastCheckedAtMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CHECKED_AT, 0L)

    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_VERSION_NAME = "version_name"
    private const val KEY_CHECKED_AT = "checked_at"
}

/**
 * A wall-clock jump backwards would otherwise park the next check a day in the future, so a
 * timestamp from the future counts as due.
 */
internal fun isUpdateCheckDue(
    lastCheckedAtMillis: Long,
    nowMillis: Long,
    intervalMillis: Long
): Boolean = lastCheckedAtMillis <= 0L ||
    nowMillis < lastCheckedAtMillis ||
    nowMillis - lastCheckedAtMillis >= intervalMillis

internal class UpdateChecker(
    private val endpoint: String? = deriveVersionEndpoint(BuildConfig.DIAGNOSTIC_INTAKE_URL)
) {
    /**
     * Returns the stored answer immediately when the interval has not elapsed. A failed fetch keeps
     * whatever was stored, so a temporary outage does not clear a banner that is still true.
     */
    suspend fun refresh(context: Context, nowMillis: Long = System.currentTimeMillis()): UpdateAvailability {
        val stored = UpdateCheckStore.read(context)
        if (!isUpdateCheckDue(
                UpdateCheckStore.lastCheckedAtMillis(context),
                nowMillis,
                CHECK_INTERVAL_MS
            )
        ) {
            return resolveUpdateAvailability(BuildConfig.VERSION_CODE.toLong(), stored)
        }
        val fetched = fetch()
        if (fetched != null) {
            UpdateCheckStore.save(context, fetched, nowMillis)
        } else {
            UpdateCheckStore.markChecked(context, nowMillis)
        }
        return resolveUpdateAvailability(BuildConfig.VERSION_CODE.toLong(), fetched ?: stored)
    }

    private suspend fun fetch(): LatestVersion? = withContext(Dispatchers.IO) {
        val target = endpoint ?: return@withContext null
        val url = runCatching { URL(target) }.getOrNull() ?: return@withContext null
        if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null ||
            url.query != null || url.ref != null || url.path != VERSION_PATH
        ) {
            return@withContext null
        }
        var connection: HttpsURLConnection? = null
        try {
            connection = url.openConnection() as? HttpsURLConnection ?: return@withContext null
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doInput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Connection", "close")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return@withContext null
            val body = connection.inputStream.use { input ->
                val buffer = ByteArray(RESPONSE_LIMIT_BYTES + 1)
                var offset = 0
                while (offset < buffer.size) {
                    val read = input.read(buffer, offset, buffer.size - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset > RESPONSE_LIMIT_BYTES) "" else buffer.copyOf(offset).toString(Charsets.UTF_8)
            }
            parseLatestVersion(body)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: IOException) {
            null
        } catch (error: Exception) {
            AppLog.w(TAG, "Update check failed", error)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "UpdateChecker"
        const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000
        const val RESPONSE_LIMIT_BYTES = 4 * 1024
    }
}
