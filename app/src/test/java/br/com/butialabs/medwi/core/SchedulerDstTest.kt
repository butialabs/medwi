package br.com.butialabs.medwi.core

import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.MedGroup
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.core.scheduler.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Daylight-saving edge cases. In a spring-forward, a wall-clock time inside the
 * skipped hour does not exist; java.time pushes it forward by the gap. The
 * scheduler must still produce exactly one valid dose and not crash.
 */
class SchedulerDstTest {

    // US Eastern: on 2026-03-08, clocks jump 02:00 -> 03:00, so 02:30 is skipped.
    private val zone: ZoneId = ZoneId.of("America/New_York")
    private val scheduler = Scheduler(zone)

    private fun millisAtStartOf(date: LocalDate) =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun spring_forward_skipped_time_is_shifted_not_dropped() {
        val springForward = LocalDate.of(2026, 3, 8)
        val group = MedGroup(
            id = 1, medicineId = 1, dosageValue = 1f, dosageUnit = "pill",
            schedule = Schedule(
                type = ScheduleType.DAILY,
                consumptionTimes = listOf(ConsumptionTime(2, 30)), // inside the gap
                startDateEpochDay = springForward.toEpochDay(),
            ),
        )

        val items = scheduler.generate(
            group,
            fromMillis = millisAtStartOf(springForward),
            toMillis = millisAtStartOf(springForward.plusDays(1)),
        )

        assertEquals(1, items.size)
        // 02:30 does not exist; the zone rules move it to 03:30 local.
        val local = Instant.ofEpochMilli(items.first().scheduledAtMillis)
            .atZone(zone).toLocalTime()
        assertEquals(LocalTime.of(3, 30), local)
    }

    @Test
    fun fall_back_duplicated_time_yields_one_dose() {
        // 2026-11-01: clocks fall 02:00 -> 01:00, so 01:30 occurs twice.
        val fallBack = LocalDate.of(2026, 11, 1)
        val group = MedGroup(
            id = 1, medicineId = 1, dosageValue = 1f, dosageUnit = "pill",
            schedule = Schedule(
                type = ScheduleType.DAILY,
                consumptionTimes = listOf(ConsumptionTime(1, 30)),
                startDateEpochDay = fallBack.toEpochDay(),
            ),
        )

        val items = scheduler.generate(
            group,
            fromMillis = millisAtStartOf(fallBack),
            toMillis = millisAtStartOf(fallBack.plusDays(1)),
        )

        assertEquals(1, items.size)
    }
}
