package br.com.butialabs.medwi.core.scheduler

import br.com.butialabs.medwi.core.model.DoseItem
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.MedGroup
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Core scheduling engine — pure Kotlin, no Android deps.
 *
 * [createOrEditItems] materializes a sliding
 * window of dose instances (~[MATERIALIZE_DAYS] days ahead) and merges with any
 * existing items so statuses the user already set (taken/skipped/snoozed) are
 * preserved across regenerations.
 *
 * @param zoneId the user's time zone; dose times are local wall-clock times.
 */
class Scheduler(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    /**
     * Generate dose items for [group] whose scheduled time falls in
     * [fromMillis, toMillis). Deterministic and side-effect free.
     */
    fun generate(group: MedGroup, fromMillis: Long, toMillis: Long): List<DoseItem> {
        if (!group.active || group.schedule.type == ScheduleType.AS_NEEDED) return emptyList()

        val schedule = group.schedule
        val strategy = Strategy.forType(schedule.type)
        val items = ArrayList<DoseItem>()

        val fromDate = Instant.ofEpochMilli(fromMillis).atZone(zoneId).toLocalDate()
        val toDate = Instant.ofEpochMilli(toMillis).atZone(zoneId).toLocalDate()
        val startDate = LocalDate.ofEpochDay(schedule.startDateEpochDay)
        val endDate = schedule.endDateEpochDay?.let(LocalDate::ofEpochDay)

        var date = maxOf(fromDate, startDate)
        while (!date.isAfter(toDate)) {
            if (endDate != null && date.isAfter(endDate)) break
            val dose = group.doseRamp
                ?.doseOn(group.dosageValue, date.toEpochDay() - schedule.startDateEpochDay)
                ?: group.dosageValue
            // A taper that reached 0 is over: nothing more to take from here on.
            if (dose <= 0f) break
            if (strategy.occursOn(date, schedule)) {
                for (time in schedule.consumptionTimes) {
                    val millis = date.atTime(time.hour, time.minute)
                        .atZone(zoneId).toInstant().toEpochMilli()
                    if (millis in fromMillis until toMillis) {
                        items += DoseItem(
                            groupId = group.id,
                            scheduledAtMillis = millis,
                            dosageValue = dose,
                            dosageUnit = group.dosageUnit,
                        )
                    }
                }
            }
            date = date.plusDays(1)
        }
        return items
    }

    /**
     * Reconcile generated items with [existing] ones for the same group,
     * materializing the standard window starting at [nowMillis].
     *
     * Rules:
     *  - Window = [nowMillis - 1 day, nowMillis + MATERIALIZE_DAYS].
     *  - An existing item the user already acted on (not PENDING) is kept as-is.
     *  - Otherwise the freshly generated PENDING item is used.
     *  - Past generated items that never existed are not resurrected.
     */
    fun createOrEditItems(
        group: MedGroup,
        existing: List<DoseItem>,
        nowMillis: Long,
    ): List<DoseItem> {
        val fromMillis = nowMillis - ONE_DAY_MILLIS
        val toMillis = nowMillis + horizonDays(group.schedule) * ONE_DAY_MILLIS

        val existingByKey = existing.associateBy { it.key }
        val generated = generate(group, fromMillis, toMillis)

        val result = LinkedHashMap<Long, DoseItem>()
        // Keep user-acted existing items (taken/skipped/snoozed) within the window.
        for (item in existing) {
            if (item.status != DoseStatus.PENDING && item.scheduledAtMillis >= fromMillis) {
                result[item.key] = item
            }
        }
        // Add freshly generated items, but never overwrite an acted-on one.
        for (item in generated) {
            val prior = existingByKey[item.key]
            if (prior != null && prior.status != DoseStatus.PENDING) continue
            result[item.key] = item
        }
        return result.values.sortedBy { it.scheduledAtMillis }
    }

    /**
     * How many days ahead to materialize. The base window is [MATERIALIZE_DAYS],
     * extended for sparse schedules so the next occurrence is always captured and
     * can arm an alarm (otherwise a monthly/every-few-weeks dose could fall just
     * past the window and never fire).
     */
    private fun horizonDays(schedule: Schedule): Long = when (schedule.type) {
        ScheduleType.EVERY_X_DAYS -> MATERIALIZE_DAYS + schedule.everyXDays
        ScheduleType.MONTHLY -> MATERIALIZE_DAYS + schedule.everyXMonths * 31L
        ScheduleType.CYCLE -> MATERIALIZE_DAYS + (schedule.cyclePillDays + schedule.cycleBreakDays)
        else -> MATERIALIZE_DAYS
    }

    companion object {
        /** Base days of dose instances kept materialized ahead of "now". */
        const val MATERIALIZE_DAYS = 32L
        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
