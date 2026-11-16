package br.com.butialabs.medwi.core

import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseRamp
import br.com.butialabs.medwi.core.model.MedGroup
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.core.scheduler.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Variable dose (taper / titration) in the scheduling engine. */
class SchedulerRampTest {

    private val zone = ZoneOffset.UTC
    private val scheduler = Scheduler(zone)
    private val day0 = LocalDate.of(2026, 1, 1)
    private fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun group(start: Float, ramp: DoseRamp) = MedGroup(
        id = 1, medicineId = 1, dosageValue = start, dosageUnit = "pill",
        schedule = Schedule(ScheduleType.DAILY, listOf(ConsumptionTime(8, 0)), day0.toEpochDay()),
        doseRamp = ramp,
    )

    private fun dosesByDay(g: MedGroup, days: Long): List<Float> =
        scheduler.generate(g, millis(day0), millis(day0.plusDays(days))).map { it.dosageValue }

    @Test
    fun taper_down_steps_every_n_days_and_stops_at_zero() {
        // 4 -> 3 -> 2 -> 1 -> stop, changing every 2 days
        val doses = dosesByDay(group(4f, DoseRamp(step = -1f, everyDays = 2, final = 0f)), days = 12)
        assertEquals(listOf(4f, 4f, 3f, 3f, 2f, 2f, 1f, 1f), doses)
    }

    @Test
    fun taper_holds_at_a_nonzero_final_dose() {
        val doses = dosesByDay(group(40f, DoseRamp(step = -10f, everyDays = 7, final = 20f)), days = 28)
        assertEquals(List(7) { 40f } + List(7) { 30f } + List(14) { 20f }, doses)
    }

    @Test
    fun titration_up_is_capped_at_final() {
        val doses = dosesByDay(group(1f, DoseRamp(step = 0.5f, everyDays = 1, final = 2f)), days = 5)
        assertEquals(listOf(1f, 1.5f, 2f, 2f, 2f), doses)
    }

    @Test
    fun doseOn_counts_whole_intervals_since_start() {
        val ramp = DoseRamp(step = -5f, everyDays = 3, final = 0f)
        assertEquals(20f, ramp.doseOn(20f, 2))
        assertEquals(15f, ramp.doseOn(20f, 3))
        assertEquals(0f, ramp.doseOn(20f, 100))
    }
}
