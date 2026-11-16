package br.com.butialabs.medwi.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.butialabs.medwi.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * Daily automatic backup to a file the user picked once (any document provider, e.g.
 * Google Drive). No Google libraries: the system file picker grants a persistent URI
 * and WorkManager rewrites that file every day.
 *
 * State lives in its own prefs file, which Android Auto Backup does not copy: the
 * granted URI only makes sense on this device.
 */
object AutoBackup {

    private const val PREFS = "auto_backup"
    private const val KEY_URI = "uri"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val DAILY_WORK = "auto_backup_daily"
    const val NOW_WORK = "auto_backup_now"
    private const val TAG = "AutoBackup"

    /** Suggested file name in the picker. */
    const val FILE_NAME = "medwi-auto-backup.json"

    data class Status(val uri: Uri?, val lastAtMillis: Long?, val lastError: String?) {
        val enabled: Boolean get() = uri != null
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun status(context: Context): Status {
        val p = prefs(context)
        return Status(
            uri = p.getString(KEY_URI, null)?.let(Uri::parse),
            lastAtMillis = p.getLong(KEY_LAST_AT, 0L).takeIf { it > 0 },
            lastError = p.getString(KEY_LAST_ERROR, null),
        )
    }

    /** Keep access to [uri] across reboots, remember it, schedule the daily job and run once now. */
    fun enable(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "No persistable permission for $uri", it) }
        prefs(context).edit().putString(KEY_URI, uri.toString()).remove(KEY_LAST_ERROR).apply()
        schedule(context)
        runNow(context)
    }

    fun disable(context: Context) {
        val uri = status(context).uri
        WorkManager.getInstance(context).cancelUniqueWork(DAILY_WORK)
        if (uri != null) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        prefs(context).edit().clear().apply()
    }

    /** (Re)schedule the daily job; KEEP leaves an existing schedule untouched. */
    fun schedule(context: Context) {
        if (!status(context).enabled) return
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun runNow(context: Context) {
        WorkManager.getInstance(context)
            .enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    /**
     * Writes a backup to the chosen file. Returns false (and records why) when there is
     * no file, access was revoked or the file disappeared; IO errors are rethrown so the
     * worker retries later.
     */
    suspend fun writeOnce(context: Context, backup: BackupManager, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val uri = status(context).uri ?: return false
        return try {
            val out = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw FileNotFoundException("Cannot open $uri")
            out.use { backup.export(it, nowMillis, BuildConfig.VERSION_NAME) }
            prefs(context).edit().putLong(KEY_LAST_AT, nowMillis).remove(KEY_LAST_ERROR).apply()
            true
        } catch (e: SecurityException) {
            recordError(context, e)
            false
        } catch (e: FileNotFoundException) {
            recordError(context, e)
            false
        }
    }

    private fun recordError(context: Context, e: Exception) {
        Log.w(TAG, "Automatic backup failed", e)
        prefs(context).edit().putString(KEY_LAST_ERROR, e.message ?: e.javaClass.simpleName).apply()
    }
}

/** Runs [AutoBackup.writeOnce]; daily and on demand ("Back up now"). */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backup: BackupManager,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (AutoBackup.writeOnce(applicationContext, backup)) Result.success() else Result.failure()
    } catch (e: java.io.IOException) {
        Result.retry()
    }
}
