package br.com.butialabs.medwi.alarm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.settings.AppSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Reminder sounds, identified by a string key stored in settings and on each medicine:
 *  - `medwi:<id>`   built-in sound shipped in res/raw (see tools/sounds/make_sounds.py)
 *  - `system:<uri>` a sound picked with the phone's ringtone picker (`system:default` = the
 *                   phone's default notification sound)
 *  - `custom:<id>`  an audio file the user uploaded
 *  - `silent`       vibrate only
 *
 * Uploaded files are kept in `filesDir/sounds` (the master copy, included in Auto Backup)
 * and published where the system's notification player can read them: the shared
 * Notifications/Medwi media folder on Android 10+, a FileProvider grant before that.
 */
object ReminderSounds {

    const val DEFAULT = AppSettings.DEFAULT_SOUND
    const val SILENT = "silent"
    private const val MEDWI = "medwi:"
    private const val SYSTEM = "system:"
    private const val CUSTOM = "custom:"

    const val MAX_BYTES = 5L * 1024 * 1024
    const val MAX_DURATION_MS = 30_000L

    private const val PREFS = "reminder_sounds"
    private const val KEY_LIST = "custom_sounds"
    private const val DIR = "sounds"
    private const val SYSTEM_UI = "com.android.systemui"

    data class BuiltIn(val id: String, @RawRes val raw: Int, @StringRes val nameRes: Int) {
        val key: String get() = MEDWI + id
    }

    /** R.raw references keep the files through resource shrinking. */
    val BUILT_INS = listOf(
        BuiltIn("chime", R.raw.medwi_chime, R.string.sound_chime),
        BuiltIn("bell", R.raw.medwi_bell, R.string.sound_bell),
        BuiltIn("soft", R.raw.medwi_soft, R.string.sound_soft),
        BuiltIn("pulse", R.raw.medwi_pulse, R.string.sound_pulse),
        BuiltIn("marimba", R.raw.medwi_marimba, R.string.sound_marimba),
    )

    @Serializable
    data class CustomSound(val id: String, val name: String, val file: String, val mime: String) {
        val key: String get() = CUSTOM + id
    }

    enum class ImportError { TOO_LARGE, TOO_LONG, INVALID }

    class ImportException(val error: ImportError) : Exception(error.name)

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------ choosing

    /**
     * The sound for a reminder covering doses of medicines with [medKeys] (null = general).
     * One medicine, or all sharing a sound: that sound; mixed sounds: the general one.
     */
    fun slotSound(medKeys: List<String?>, global: String): String =
        medKeys.map { it ?: global }.distinct().singleOrNull() ?: global

    /** Key for a ringtone-picker result; null (the picker's "None") means silent. */
    fun systemKey(uri: Uri?): String = when (uri) {
        null -> SILENT
        Settings.System.DEFAULT_NOTIFICATION_URI -> DEFAULT
        else -> SYSTEM + uri
    }

    fun isCustom(key: String?): Boolean = key?.startsWith(CUSTOM) == true
    fun isSystem(key: String?): Boolean = key?.startsWith(SYSTEM) == true

    /** Whether [key] can still be played on this device (an uploaded file may be gone). */
    fun isAvailable(context: Context, key: String): Boolean = when {
        key == SILENT || key.startsWith(SYSTEM) -> true
        key.startsWith(MEDWI) -> builtIn(key) != null
        key.startsWith(CUSTOM) -> custom(context, key)?.let { fileOf(context, it).isFile } == true
        else -> false
    }

    /** [key] if playable, else the general sound, else the phone default. */
    fun effective(context: Context, key: String): String = when {
        isAvailable(context, key) -> key
        else -> AppSettings.reminderSound(context).takeIf { isAvailable(context, it) } ?: DEFAULT
    }

    /** The URI the notification channel plays for [key]; null = no sound. */
    fun uriFor(context: Context, key: String): Uri? = when {
        key == SILENT -> null
        key == DEFAULT -> Settings.System.DEFAULT_NOTIFICATION_URI
        key.startsWith(SYSTEM) -> Uri.parse(key.removePrefix(SYSTEM))
        key.startsWith(MEDWI) -> builtIn(key)?.let { rawUri(context, it.raw) }
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        key.startsWith(CUSTOM) -> custom(context, key)?.let { publish(context, it) }
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        else -> Settings.System.DEFAULT_NOTIFICATION_URI
    }

    /** URI for previewing [key] inside the app (no publishing needed). */
    fun previewUri(context: Context, key: String): Uri? = when {
        key.startsWith(CUSTOM) -> custom(context, key)?.let { Uri.fromFile(fileOf(context, it)) }
        else -> uriFor(context, key)
    }

    fun displayName(context: Context, key: String): String = when {
        key == SILENT -> context.getString(R.string.sound_silent)
        key == DEFAULT -> context.getString(R.string.sound_phone_default)
        key.startsWith(MEDWI) -> builtIn(key)?.let { context.getString(it.nameRes) }
            ?: context.getString(R.string.sound_phone_default)
        key.startsWith(CUSTOM) -> custom(context, key)?.name ?: context.getString(R.string.sound_missing)
        key.startsWith(SYSTEM) -> runCatching {
            RingtoneManager.getRingtone(context, Uri.parse(key.removePrefix(SYSTEM)))?.getTitle(context)
        }.getOrNull() ?: context.getString(R.string.sound_phone)
        else -> context.getString(R.string.sound_phone_default)
    }

    private fun builtIn(key: String): BuiltIn? = BUILT_INS.firstOrNull { it.key == key }

    private fun rawUri(context: Context, @RawRes raw: Int): Uri =
        // By name, not id: resource ids may change between versions, and the URI is part
        // of a notification channel that outlives updates.
        Uri.parse("android.resource://${context.packageName}/raw/${context.resources.getResourceEntryName(raw)}")

    // ------------------------------------------------------------------ uploaded sounds

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun listCustom(context: Context): List<CustomSound> =
        prefs(context).getString(KEY_LIST, null)?.let {
            runCatching { json.decodeFromString<List<CustomSound>>(it) }.getOrNull()
        } ?: emptyList()

    private fun saveCustom(context: Context, list: List<CustomSound>) =
        prefs(context).edit().putString(KEY_LIST, json.encodeToString(list)).apply()

    private fun custom(context: Context, key: String): CustomSound? =
        listCustom(context).firstOrNull { it.key == key }

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileOf(context: Context, sound: CustomSound) = File(dir(context), sound.file)

    /**
     * Copy an audio file picked by the user into the app and register it. Blocking (I/O);
     * call off the main thread. Returns the new sound's key.
     */
    fun importCustom(context: Context, source: Uri): String {
        val resolver = context.contentResolver
        var name = "Sound"
        var size = -1L
        resolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        if (size > MAX_BYTES) throw ImportException(ImportError.TOO_LARGE)
        val mime = resolver.getType(source)?.takeIf { it.startsWith("audio/") }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?.takeIf { it.startsWith("audio/") }
            ?: throw ImportException(ImportError.INVALID)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: name.substringAfterLast('.', "").ifBlank { "audio" }

        val id = UUID.randomUUID().toString().take(8)
        val file = File(dir(context), "$id.$ext")
        try {
            val input = resolver.openInputStream(source) ?: throw ImportException(ImportError.INVALID)
            input.use { inp ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw ImportException(ImportError.TOO_LARGE)
                        out.write(buf, 0, n)
                    }
                }
            }
            val duration = durationMs(file) ?: throw ImportException(ImportError.INVALID)
            if (duration <= 0) throw ImportException(ImportError.INVALID)
            if (duration > MAX_DURATION_MS) throw ImportException(ImportError.TOO_LONG)
        } catch (e: Exception) {
            file.delete()
            throw if (e is ImportException) e else ImportException(ImportError.INVALID)
        }

        val sound = CustomSound(id, name.substringBeforeLast('.').ifBlank { name }, file.name, mime)
        saveCustom(context, listCustom(context) + sound)
        publish(context, sound)
        return sound.key
    }

    private fun durationMs(file: File): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /**
     * Remove an uploaded sound. Callers reset medicines using it; the general sound goes
     * back to the default here.
     */
    fun deleteCustom(context: Context, key: String) {
        val sound = custom(context, key) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findPublished(context, sound)?.let { uri ->
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
        fileOf(context, sound).delete()
        saveCustom(context, listCustom(context).filterNot { it.id == sound.id })
        if (AppSettings.reminderSound(context) == key) AppSettings.setReminderSound(context, DEFAULT)
    }

    // ------------------------------------------------------------------ publishing

    private fun publishedName(sound: CustomSound) = "medwi-${sound.id}.${sound.file.substringAfterLast('.')}"

    private val relativePath = Environment.DIRECTORY_NOTIFICATIONS + "/Medwi/"

    /**
     * A URI the system can play for [sound], (re)publishing it if needed. Null when the
     * master file is gone.
     */
    private fun publish(context: Context, sound: CustomSound): Uri? {
        val file = fileOf(context, sound)
        if (!file.isFile) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findPublished(context, sound) ?: runCatching { insertMedia(context, sound, file) }.getOrNull()
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.sounds", file)
            // The notification sound is played by SystemUI, which needs read access.
            context.grantUriPermission(SYSTEM_UI, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        }
    }

    /** Our copy in the shared media store, if the user didn't delete it (Android 10+). */
    private fun findPublished(context: Context, sound: CustomSound): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
                arrayOf(publishedName(sound), relativePath),
                null,
            )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
        }.getOrNull()
    }

    private fun insertMedia(context: Context, sound: CustomSound, file: File): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, publishedName(sound))
            put(MediaStore.Audio.Media.TITLE, sound.name)
            put(MediaStore.Audio.Media.MIME_TYPE, sound.mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
