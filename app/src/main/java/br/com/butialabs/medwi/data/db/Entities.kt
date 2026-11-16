package br.com.butialabs.medwi.data.db

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Room entities: medicine -> schedule group (the dosing recipe) -> dose items.
 */

@Serializable
@Entity(tableName = "medicine")
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val form: String? = null,          // tablet, capsule, injection...
    val strengthValue: String? = null, // "500"
    val strengthUnit: String? = null,  // "mg"
    val color: String? = null,
    val shape: String? = null,
    val foodInstruction: Int = 0,      // 0=none, 1=before, 2=with, 3=after
    val extId: String? = null,         // agency id (NDC / ANVISA registro)
    val imageUrl: String? = null,
    val iconKey: String? = null,       // pill shape key (see PillShape)
    val instructions: String? = null,  // free-text "take with food", etc.
    val stockCount: Int? = null,       // current units on hand (refill tracking)
    val refillThreshold: Int? = null,  // warn when stockCount <= this
    val unitsPerPackage: Int? = null,  // units added by "+1 box"
    val refillReminderTime: String? = null, // "HH:mm" daily refill reminder while stock is low
    val color2: String? = null,        // second color (two-tone capsules, bottle caps...)
    val reminderSound: String? = null, // reminder sound key (see ReminderSounds); null = general sound
)

@Serializable
@Entity(
    tableName = "schedule_group",
    foreignKeys = [
        ForeignKey(
            entity = MedicineEntity::class,
            parentColumns = ["id"],
            childColumns = ["medicineId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("medicineId")],
)
data class ScheduleGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val dosageValue: Float = 1f,
    val dosageUnit: String = "pill",
    // ---- schedule (flattened Schedule) ----
    val scheduleType: String,            // ScheduleType.name
    val consumptionTimes: String,        // CSV "08:00,20:00"
    val startDateEpochDay: Long,
    val endDateEpochDay: Long? = null,
    val everyXDays: Int = 1,
    val daysOfWeek: String = "",         // CSV "1,3,5"
    val cyclePillDays: Int = 0,
    val cycleBreakDays: Int = 0,
    val everyXMonths: Int = 1,
    /** Spacing rule used by the wizard's auto-organizer; null = times chosen freely. */
    val doseIntervalMinutes: Int? = null,
    val active: Boolean = true,
    /** Set while the treatment is paused (no doses generated). */
    val pausedAtMillis: Long? = null,
    /** Set once the treatment was ended; history is kept. */
    val endedAtMillis: Long? = null,
    // ---- variable dose / taper: dose += doseStep every doseStepEveryDays, until doseFinal ----
    val doseStep: Float? = null,
    val doseStepEveryDays: Int? = null,
    val doseFinal: Float? = null,
)

@Serializable
@Entity(
    tableName = "dose_item",
    foreignKeys = [
        ForeignKey(
            entity = ScheduleGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("groupId"), Index(value = ["groupId", "scheduledAtMillis"], unique = true)],
)
data class DoseItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    /** Planned time. Immutable: together with [groupId] it is the dose's stable identity. */
    val scheduledAtMillis: Long,
    val actualAtMillis: Long? = null,
    /** When snoozed, the time the reminder should fire again (planned time is kept). */
    val snoozedUntilMillis: Long? = null,
    val dosageValue: Float,
    val dosageUnit: String,
    val status: String,          // DoseStatus.name
    val snoozeCounter: Int = 0,
    val note: String? = null,
)

/** When the reminder for this dose is due: the snooze target, or the planned time. */
fun DoseItemEntity.effectiveAtMillis(): Long = snoozedUntilMillis ?: scheduledAtMillis
