package br.com.butialabs.medwi.core

import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.MedGroup
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.core.scheduler.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SchedulerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val scheduler = Scheduler(zone)
    private val day0 = LocalDate.of(2026, 1, 1)
    private fun millis(date: LocalDate) =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun group(schedule: Schedule) = MedGroup(
        id = 1, medicineId = 1, dosageValue = 1f, dosageUnit = "pill", schedule = schedule,
    )

    @Test
    fun daily_twice_a_day_generates_two_per_day() {
        val g = group(
            Schedule(
                type = ScheduleType.DAILY,
                consumptionTimes = listOf(ConsumptionTime(8, 0), ConsumptionTime(20, 0)),
                startDateEpochDay = day0.toEpochDay(),
            )
        )
        val from = millis(day0)
        val to = millis(day0.plusDays(3))
        val items = scheduler.generate(g, from, to)
        assertEquals(6, items.size) // 2/day * 3 days
    }

    @Test
    fun every_x_days_respects_interval() {
        val g = group(
            Schedule(
                type = ScheduleType.EVERY_X_DAYS,
                consumptionTimes = listOf(ConsumptionTime(9, 0)),
                startDateEpochDay = day0.toEpochDay(),
                everyXDays = 3,
            )
        )
        val items = scheduler.generate(g, millis(day0), millis(day0.plusDays(9)))
        // days 0,3,6 within [0,9) -> 3 doses
        assertEquals(3, items.size)
    }

    @Test
    fun days_of_week_only_selected_days() {
        // Jan 1 2026 is a Thursday (ISO 4). Pick Mon(1) and Fri(5).
        val g = group(
            Schedule(
                type = ScheduleType.DAYS_OF_WEEK,
                consumptionTimes = listOf(ConsumptionTime(7, 30)),
                startDateEpochDay = day0.toEpochDay(),
                daysOfWeek = setOf(1, 5),
            )
        )
        val items = scheduler.generate(g, millis(day0), millis(day0.plusDays(7)))
        // In Jan 1..7: Fri Jan 2 and Mon Jan 5 -> 2 doses
        assertEquals(2, items.size)
    }

    @Test
    fun cycle_takes_then_breaks() {
        val g = group(
            Schedule(
                type = ScheduleType.CYCLE,
                consumptionTimes = listOf(ConsumptionTime(8, 0)),
                startDateEpochDay = day0.toEpochDay(),
                cyclePillDays = 3,
                cycleBreakDays = 2,
            )
        )
        val items = scheduler.generate(g, millis(day0), millis(day0.plusDays(5)))
        // days 0,1,2 taken; 3,4 break -> 3 doses
        assertEquals(3, items.size)
    }

    @Test
    fun as_needed_generates_nothing() {
        val g = group(
            Schedule(
                type = ScheduleType.AS_NEEDED,
                consumptionTimes = emptyList(),
                startDateEpochDay = day0.toEpochDay(),
            )
        )
        assertTrue(scheduler.generate(g, millis(day0), millis(day0.plusDays(30))).isEmpty())
    }

    @Test
    fun reconcile_preserves_taken_status() {
        val g = group(
            Schedule(
                type = ScheduleType.DAILY,
                consumptionTimes = listOf(ConsumptionTime(8, 0)),
                startDateEpochDay = day0.toEpochDay(),
            )
        )
        val now = millis(day0.plusDays(1)) + 9 * 60 * 60 * 1000 // day1 09:00
        val first = scheduler.createOrEditItems(g, emptyList(), now)
        assertTrue(first.isNotEmpty())
        // Mark the earliest dose as taken, then regenerate.
        val taken = first.first().copy(status = DoseStatus.TAKEN)
        val merged = scheduler.createOrEditItems(g, listOf(taken) + first.drop(1), now)
        val keptTaken = merged.firstOrNull { it.key == taken.key }
        assertEquals(DoseStatus.TAKEN, keptTaken?.status)
    }

    @Test
    fun window_is_about_32_days() {
        val g = group(
            Schedule(
                type = ScheduleType.DAILY,
                consumptionTimes = listOf(ConsumptionTime(8, 0)),
                startDateEpochDay = day0.toEpochDay(),
            )
        )
        val now = millis(day0)
        val items = scheduler.createOrEditItems(g, emptyList(), now)
        // ~32 days ahead, one per day.
        assertTrue("expected ~32 items, got ${items.size}", items.size in 31..34)
    }
}
