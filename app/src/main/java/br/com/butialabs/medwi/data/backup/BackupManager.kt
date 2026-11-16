package br.com.butialabs.medwi.data.backup

import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.db.DoseItemEntity
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.RefillEventEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import androidx.room3.withWriteTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Versioned, schema-independent snapshot of everything the user entered. Kept as plain
 * JSON so a file exported by one app version can be restored by a later one.
 */
@Serializable
data class MedwiBackup(
    val format: Int = FORMAT,
    val exportedAt: Long,
    val appVersion: String = "",
    val medicines: List<MedicineEntity>,
    val groups: List<ScheduleGroupEntity>,
    val doses: List<DoseItemEntity>,
    val refills: List<RefillEventEntity> = emptyList(),
    /** General reminder sound key; null in files from before selectable sounds. */
    val reminderSound: String? = null,
) {
    companion object {
        const val FORMAT = 1
    }
}

/** Exports/imports a [MedwiBackup] through any stream (SAF file, Google Drive...). */
class BackupManager(
    private val db: AppDatabase,
    private val repository: MedRepository,
    private val settings: SoundSetting = SoundSetting.NONE,
) {
    /** Where the general reminder sound lives (SharedPreferences in the app). */
    interface SoundSetting {
        fun get(): String?
        fun set(key: String)

        companion object {
            val NONE = object : SoundSetting {
                override fun get(): String? = null
                override fun set(key: String) = Unit
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    suspend fun snapshot(nowMillis: Long = System.currentTimeMillis(), appVersion: String = ""): MedwiBackup =
        MedwiBackup(
            exportedAt = nowMillis,
            appVersion = appVersion,
            medicines = db.medicineDao().all(),
            groups = db.scheduleGroupDao().all(),
            doses = db.doseItemDao().all(),
            refills = db.refillEventDao().all(),
            reminderSound = settings.get(),
        )

    suspend fun export(out: OutputStream, nowMillis: Long = System.currentTimeMillis(), appVersion: String = "") {
        val text = json.encodeToString(MedwiBackup.serializer(), snapshot(nowMillis, appVersion))
        out.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    /** Parse and sanity-check a backup file without touching the database (for the preview). */
    fun read(input: InputStream): MedwiBackup {
        val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val backup = json.decodeFromString(MedwiBackup.serializer(), text)
        require(backup.format in 1..MedwiBackup.FORMAT) { "Unsupported backup format ${backup.format}" }
        val medIds = backup.medicines.mapTo(HashSet()) { it.id }
        val groupIds = backup.groups.mapTo(HashSet()) { it.id }
        require(backup.groups.all { it.medicineId in medIds }) { "Backup has schedules without medicine" }
        require(backup.doses.all { it.groupId in groupIds }) { "Backup has doses without schedule" }
        return backup
    }

    /**
     * Replace all data with [backup] in one transaction, then rebuild what isn't stored:
     * future doses, the next alarm, the refill reminder and the widget.
     */
    suspend fun restore(backup: MedwiBackup, nowMillis: Long = System.currentTimeMillis()) {
        db.withWriteTransaction {
            db.medicineDao().deleteAll() // cascades groups, doses, refills
            db.medicineDao().insertAll(backup.medicines)
            db.scheduleGroupDao().insertAll(backup.groups)
            db.doseItemDao().insertAll(backup.doses)
            db.refillEventDao().insertAll(backup.refills)
        }
        backup.reminderSound?.let(settings::set)
        repository.regenerateAll(nowMillis)
        repository.pruneSoundChannels()
    }
}
