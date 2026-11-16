package br.com.butialabs.medwi.data

import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseItem
import br.com.butialabs.medwi.core.model.DoseRamp
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.MedGroup
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.data.db.DoseItemEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity

/** Entity <-> core-model mapping, kept in one place. */

fun ScheduleGroupEntity.toCore(): MedGroup = MedGroup(
    id = id,
    medicineId = medicineId,
    dosageValue = dosageValue,
    dosageUnit = dosageUnit,
    active = active,
    doseRamp = doseRamp(),
    schedule = Schedule(
        type = ScheduleType.valueOf(scheduleType),
        consumptionTimes = ConsumptionTime.parseList(consumptionTimes),
        startDateEpochDay = startDateEpochDay,
        endDateEpochDay = endDateEpochDay,
        everyXDays = everyXDays,
        daysOfWeek = daysOfWeek.split(",")
            .mapNotNull { it.trim().toIntOrNull() }.toSet(),
        cyclePillDays = cyclePillDays,
        cycleBreakDays = cycleBreakDays,
        everyXMonths = everyXMonths,
    ),
)

/** The persisted variable-dose rule, if complete and valid. */
fun ScheduleGroupEntity.doseRamp(): DoseRamp? {
    val step = doseStep ?: return null
    val every = doseStepEveryDays ?: return null
    val final = doseFinal ?: return null
    if (step == 0f || every < 1 || final < 0f) return null
    return DoseRamp(step, every, final)
}

fun DoseItemEntity.toCore(): DoseItem = DoseItem(
    groupId = groupId,
    scheduledAtMillis = scheduledAtMillis,
    dosageValue = dosageValue,
    dosageUnit = dosageUnit,
    status = DoseStatus.valueOf(status),
    key = scheduledAtMillis,
)

fun DoseItem.toEntity(existingId: Long = 0): DoseItemEntity = DoseItemEntity(
    id = existingId,
    groupId = groupId,
    scheduledAtMillis = scheduledAtMillis,
    dosageValue = dosageValue,
    dosageUnit = dosageUnit,
    status = status.name,
)
