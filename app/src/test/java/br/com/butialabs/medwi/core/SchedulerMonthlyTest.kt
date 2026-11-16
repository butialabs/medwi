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
import java.time.ZoneId
import java.time.ZoneOffset

/** Monthly recurrence: interval, anchor day, and short-month clamping. */
class SchedulerMonthlyTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val scheduler = Scheduler(zone)

    private fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun datesOf(group: MedGroup, from: LocalDate, to: LocalDate): List<LocalDate> =
        scheduler.generate(group, millis(from), millis(to)).map {
            Instant.ofEpochMilli(it.scheduledAtMillis).atZone(zone).toLocalDate()
        }

    private fun monthly(start: LocalDate, everyXMonths: Int) = MedGroup(
        id = 1, medicineId = 1, dosageValue = 1f, dosageUnit = "pill",
        schedule = Schedule(
            type = ScheduleType.MONTHLY,
            consumptionTimes = listOf(ConsumptionTime(8, 0)),
            startDateEpochDay = start.toEpochDay(),
            everyXMonths = everyXMonths,
        ),
    )

    @Test
    fun every_month_on_anchor_day() {
        val g = monthly(LocalDate.of(2026, 1, 15), 1)
        val dates = datesOf(g, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))
        assertEquals(
            listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 15)),
            dates,
        )
    }

    @Test
    fun every_three_months_skips_in_between() {
        val g = monthly(LocalDate.of(2026, 1, 10), 3)
        val dates = datesOf(g, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1))
        assertEquals(
            listOf(LocalDate.of(2026, 1, 10), LocalDate.of(2026, 4, 10), LocalDate.of(2026, 7, 10)),
            dates,
        )
    }

    @Test
    fun anchor_day_31_clamps_to_short_months() {
        val g = monthly(LocalDate.of(2026, 1, 31), 1)
        val dates = datesOf(g, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1))
        // 2026 is not a leap year -> Feb clamps to the 28th; Mar back to 31.
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
            dates,
        )
    }
}
