package br.com.butialabs.medwi.data.db

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {
    @Insert
    suspend fun insert(medicine: MedicineEntity): Long

    @Query("SELECT * FROM medicine WHERE id = :id")
    suspend fun byId(id: Long): MedicineEntity?

    @Update
    suspend fun update(medicine: MedicineEntity)

    @Delete
    suspend fun delete(medicine: MedicineEntity)

    @Query("SELECT * FROM medicine")
    suspend fun all(): List<MedicineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medicines: List<MedicineEntity>)

    /** Wipes everything (groups, doses, refills cascade). Used by backup restore. */
    @Query("DELETE FROM medicine")
    suspend fun deleteAll()

    /** Medicines with their schedule state, for the Ativos / Pausados / Encerrados list. */
    @Query(
        """
        SELECT m.*, g.id AS groupId, g.scheduleType AS scheduleType, g.active AS groupActive,
               g.pausedAtMillis AS pausedAtMillis, g.endedAtMillis AS endedAtMillis
        FROM medicine m
        LEFT JOIN schedule_group g ON g.medicineId = m.id
        ORDER BY m.name COLLATE NOCASE
        """
    )
    fun observeListItems(): Flow<List<MedListItem>>

    @Query("SELECT * FROM medicine WHERE id = :id")
    fun observeById(id: Long): Flow<MedicineEntity?>

    /** Medicines of running treatments whose stock is at/below their refill threshold. */
    @Query(
        """
        SELECT m.* FROM medicine m
        JOIN schedule_group g ON g.medicineId = m.id
        WHERE g.active = 1 AND m.stockCount IS NOT NULL AND m.refillThreshold IS NOT NULL
          AND m.stockCount <= m.refillThreshold
        """
    )
    suspend fun lowStock(): List<MedicineEntity>

    @Query("UPDATE medicine SET reminderSound = :sound WHERE id = :id")
    suspend fun setReminderSound(id: Long, sound: String?)

    /** Per-medicine sounds in use (for pruning notification channels). */
    @Query("SELECT DISTINCT reminderSound FROM medicine WHERE reminderSound IS NOT NULL")
    suspend fun reminderSoundsInUse(): List<String>

    /** Medicines using a sound that is being deleted go back to the general sound. */
    @Query("UPDATE medicine SET reminderSound = NULL WHERE reminderSound = :sound")
    suspend fun clearReminderSound(sound: String)
}

@Dao
interface ScheduleGroupDao {
    @Insert
    suspend fun insert(group: ScheduleGroupEntity): Long

    @Update
    suspend fun update(group: ScheduleGroupEntity)

    @Query("SELECT * FROM schedule_group WHERE active = 1")
    suspend fun allActive(): List<ScheduleGroupEntity>

    @Query("SELECT * FROM schedule_group WHERE id = :id")
    suspend fun byId(id: Long): ScheduleGroupEntity?

    @Query("SELECT * FROM schedule_group WHERE medicineId = :medicineId LIMIT 1")
    suspend fun forMedicine(medicineId: Long): ScheduleGroupEntity?

    @Query("SELECT * FROM schedule_group WHERE medicineId = :medicineId LIMIT 1")
    fun observeForMedicine(medicineId: Long): Flow<ScheduleGroupEntity?>

    @Query("SELECT * FROM schedule_group")
    suspend fun all(): List<ScheduleGroupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(groups: List<ScheduleGroupEntity>)

    @Query(
        """
        SELECT g.id AS groupId, g.medicineId AS medicineId,
               g.dosageValue AS dosageValue, g.dosageUnit AS dosageUnit,
               m.name AS name, m.iconKey AS iconKey, m.color AS colorHex, m.color2 AS color2Hex,
               m.strengthValue AS strengthValue, m.strengthUnit AS strengthUnit
        FROM schedule_group g
        JOIN medicine m ON m.id = g.medicineId
        WHERE g.scheduleType = 'AS_NEEDED' AND g.active = 1
        ORDER BY m.name
        """
    )
    fun observeAsNeeded(): Flow<List<AsNeededMed>>
}

@Dao
interface RefillEventDao {
    @Insert
    suspend fun insert(event: RefillEventEntity): Long

    @Query("SELECT * FROM refill_event WHERE medicineId = :medicineId ORDER BY atMillis DESC")
    fun observeForMedicine(medicineId: Long): Flow<List<RefillEventEntity>>

    @Query("SELECT * FROM refill_event")
    suspend fun all(): List<RefillEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<RefillEventEntity>)
}

@Dao
interface DoseItemDao {
    @Upsert
    suspend fun upsertAll(items: List<DoseItemEntity>)

    @Query("SELECT * FROM dose_item WHERE groupId = :groupId")
    suspend fun forGroup(groupId: Long): List<DoseItemEntity>

    @Query(
        """
        SELECT d.id AS itemId, d.groupId AS groupId, d.scheduledAtMillis AS scheduledAtMillis,
               d.actualAtMillis AS actualAtMillis, d.note AS note,
               d.dosageValue AS dosageValue, d.dosageUnit AS dosageUnit, d.status AS status,
               m.name AS medicineName, m.form AS form,
               m.strengthValue AS strengthValue, m.strengthUnit AS strengthUnit,
               m.iconKey AS iconKey, m.color AS colorHex, m.color2 AS color2Hex,
               m.reminderSound AS reminderSound
        FROM dose_item d
        JOIN schedule_group g ON g.id = d.groupId
        JOIN medicine m ON m.id = g.medicineId
        WHERE d.scheduledAtMillis BETWEEN :from AND :to
        ORDER BY d.scheduledAtMillis
        """
    )
    fun observeViewsBetween(from: Long, to: Long): Flow<List<DoseView>>

    @Query(
        """
        SELECT d.id AS itemId, d.groupId AS groupId, d.scheduledAtMillis AS scheduledAtMillis,
               d.actualAtMillis AS actualAtMillis, d.note AS note,
               d.dosageValue AS dosageValue, d.dosageUnit AS dosageUnit, d.status AS status,
               m.name AS medicineName, m.form AS form,
               m.strengthValue AS strengthValue, m.strengthUnit AS strengthUnit,
               m.iconKey AS iconKey, m.color AS colorHex, m.color2 AS color2Hex,
               m.reminderSound AS reminderSound
        FROM dose_item d
        JOIN schedule_group g ON g.id = d.groupId
        JOIN medicine m ON m.id = g.medicineId
        WHERE d.scheduledAtMillis BETWEEN :from AND :to
        ORDER BY d.scheduledAtMillis
        """
    )
    suspend fun viewsBetween(from: Long, to: Long): List<DoseView>

    @Query(
        """
        SELECT d.id AS itemId, d.groupId AS groupId, d.scheduledAtMillis AS scheduledAtMillis,
               d.actualAtMillis AS actualAtMillis, d.note AS note,
               d.dosageValue AS dosageValue, d.dosageUnit AS dosageUnit, d.status AS status,
               m.name AS medicineName, m.form AS form,
               m.strengthValue AS strengthValue, m.strengthUnit AS strengthUnit,
               m.iconKey AS iconKey, m.color AS colorHex, m.color2 AS color2Hex,
               m.reminderSound AS reminderSound
        FROM dose_item d
        JOIN schedule_group g ON g.id = d.groupId
        JOIN medicine m ON m.id = g.medicineId
        WHERE d.id = :itemId
        """
    )
    suspend fun viewById(itemId: Long): DoseView?

    /** Un-acted doses of a group planned after [after] (dropped when pausing / ending). */
    @Query(
        "SELECT * FROM dose_item WHERE groupId = :groupId AND status IN ('PENDING', 'SNOOZED') " +
            "AND scheduledAtMillis > :after"
    )
    suspend fun futureUnanswered(groupId: Long, after: Long): List<DoseItemEntity>

    @Query("SELECT * FROM dose_item")
    suspend fun all(): List<DoseItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoseItemEntity>)

    /** Soonest un-acted dose whose reminder (snooze target or planned time) is after [after]. */
    @Query(
        "SELECT * FROM dose_item WHERE status IN ('PENDING', 'SNOOZED') " +
            "AND COALESCE(snoozedUntilMillis, scheduledAtMillis) > :after " +
            "ORDER BY COALESCE(snoozedUntilMillis, scheduledAtMillis) LIMIT 1"
    )
    suspend fun nextPending(after: Long): DoseItemEntity?

    /** Every un-acted dose whose reminder is due exactly at [at] (several meds can share a time). */
    @Query(
        "SELECT * FROM dose_item WHERE status IN ('PENDING', 'SNOOZED') " +
            "AND COALESCE(snoozedUntilMillis, scheduledAtMillis) = :at"
    )
    suspend fun dueAt(at: Long): List<DoseItemEntity>

    /** Lookup by stable identity (group + planned time), used when an id went stale. */
    @Query("SELECT * FROM dose_item WHERE groupId = :groupId AND scheduledAtMillis = :scheduledAt LIMIT 1")
    suspend fun byKey(groupId: Long, scheduledAt: Long): DoseItemEntity?

    @Query("DELETE FROM dose_item WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM dose_item WHERE id = :id")
    suspend fun byId(id: Long): DoseItemEntity?

    @Update
    suspend fun update(item: DoseItemEntity)
}
