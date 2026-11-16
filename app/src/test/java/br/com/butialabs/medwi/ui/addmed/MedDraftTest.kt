package br.com.butialabs.medwi.ui.addmed

import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pure-JVM checks for the draft -> domain builders (no Android runtime needed). */
class MedDraftTest {

    private val start = LocalDate.of(2026, 1, 1).toEpochDay()

    @Test
    fun every_day_keeps_times() {
        val d = MedDraft(
            frequency = FrequencyChoice.EVERY_DAY,
            times = listOf(TimeOfDay(8, 0), TimeOfDay(20, 0)),
        )
        val s = d.toSchedule(start)
        assertEquals(ScheduleType.DAILY, s.type)
        assertEquals(listOf(ConsumptionTime(8, 0), ConsumptionTime(20, 0)), s.consumptionTimes)
    }

    @Test
    fun alternate_days_maps_to_every_two_days() {
        val s = MedDraft(frequency = FrequencyChoice.ALTERNATE_DAYS).toSchedule(start)
        assertEquals(ScheduleType.EVERY_X_DAYS, s.type)
        assertEquals(2, s.everyXDays)
    }

    @Test
    fun every_x_weeks_becomes_seven_times_x_days() {
        val s = MedDraft(frequency = FrequencyChoice.EVERY_X_WEEKS, everyX = 3).toSchedule(start)
        assertEquals(ScheduleType.EVERY_X_DAYS, s.type)
        assertEquals(21, s.everyXDays)
    }

    @Test
    fun every_x_months_maps_to_monthly() {
        val s = MedDraft(frequency = FrequencyChoice.EVERY_X_MONTHS, everyX = 2).toSchedule(start)
        assertEquals(ScheduleType.MONTHLY, s.type)
        assertEquals(2, s.everyXMonths)
    }

    @Test
    fun as_needed_has_no_times() {
        val s = MedDraft(frequency = FrequencyChoice.AS_NEEDED).toSchedule(start)
        assertEquals(ScheduleType.AS_NEEDED, s.type)
        assertTrue(s.consumptionTimes.isEmpty())
    }

    @Test
    fun duration_sets_inclusive_end_date() {
        val continuous = MedDraft(frequency = FrequencyChoice.EVERY_DAY, durationDays = null).toSchedule(start)
        assertNull(continuous.endDateEpochDay)
        val bounded = MedDraft(frequency = FrequencyChoice.EVERY_DAY, durationDays = 10).toSchedule(start)
        assertEquals(start + 9, bounded.endDateEpochDay) // 10 inclusive days
    }

    @Test
    fun to_medicine_carries_form_token_and_extras() {
        val d = MedDraft(
            name = "  Dipirona ",
            form = MedForm.OTHER, formOther = " Xarope ",
            strengthValue = "500", strengthUnit = "mg",
            instructions = " com água ", stockCount = "30", refillThreshold = "5",
            iconKey = "dropper",
        )
        val m = d.toMedicine()
        assertEquals("Dipirona", m.name)
        assertEquals("Xarope", m.form)
        assertEquals("500", m.strengthValue)
        assertEquals("com água", m.instructions)
        assertEquals(30, m.stockCount)
        assertEquals(5, m.refillThreshold)
        assertEquals("dropper", m.iconKey)
    }

    @Test
    fun dosage_value_parses_comma_and_defaults() {
        assertEquals(2.5f, MedDraft(dosageQty = "2,5").dosageValue, 0.0001f)
        assertEquals(1f, MedDraft(dosageQty = "abc").dosageValue, 0.0001f)
    }

    @Test
    fun from_existing_reconstructs_days_of_week_draft() {
        val med = MedicineEntity(
            id = 5, name = "Aspirin", form = "injection",
            strengthValue = "100", strengthUnit = "mg",
            instructions = "with food", stockCount = 20, refillThreshold = 5,
            iconKey = "drop", color = "#40617E",
        )
        val group = ScheduleGroupEntity(
            id = 9, medicineId = 5, dosageValue = 2f, dosageUnit = "injection",
            scheduleType = "DAYS_OF_WEEK", consumptionTimes = "08:00,20:00",
            startDateEpochDay = 100, daysOfWeek = "1,3,5",
        )

        val d = MedDraft.fromExisting(med, group)

        assertEquals("Aspirin", d.name)
        assertEquals(MedForm.INJECTION, d.form)
        assertEquals(FrequencyChoice.DAYS_OF_WEEK, d.frequency)
        assertEquals(setOf(1, 3, 5), d.daysOfWeek)
        assertEquals(listOf(TimeOfDay(8, 0), TimeOfDay(20, 0)), d.times)
        assertEquals("with food", d.instructions)
        assertEquals("20", d.stockCount)
        assertEquals("dropper", d.iconKey) // legacy "drop" icon -> dropper drawing
        assertEquals("2", d.dosageQty)
    }

    @Test
    fun from_existing_maps_intervals_back() {
        fun group(type: String, xDays: Int, xMonths: Int = 1) = ScheduleGroupEntity(
            medicineId = 1, scheduleType = type, consumptionTimes = "09:00",
            startDateEpochDay = 0, everyXDays = xDays, everyXMonths = xMonths,
        )
        val med = MedicineEntity(name = "X")

        // every 14 days -> every 2 weeks
        MedDraft.fromExisting(med, group("EVERY_X_DAYS", xDays = 14)).let {
            assertEquals(FrequencyChoice.EVERY_X_WEEKS, it.frequency)
            assertEquals(2, it.everyX)
        }
        // every 2 days -> alternate days
        assertEquals(
            FrequencyChoice.ALTERNATE_DAYS,
            MedDraft.fromExisting(med, group("EVERY_X_DAYS", xDays = 2)).frequency,
        )
        // monthly every 3 months
        MedDraft.fromExisting(med, group("MONTHLY", xDays = 1, xMonths = 3)).let {
            assertEquals(FrequencyChoice.EVERY_X_MONTHS, it.frequency)
            assertEquals(3, it.everyX)
        }
    }

    // ---- interval organizer ----

    @Test
    fun distribute_three_doses_every_eight_hours_wraps_midnight() {
        assertEquals(
            listOf(TimeOfDay(8, 0), TimeOfDay(16, 0), TimeOfDay(0, 0)),
            distributeTimes(TimeOfDay(8, 0), count = 3, intervalMinutes = 480),
        )
    }

    @Test
    fun five_doses_per_day_are_4h48_apart() {
        val d = MedDraft(firstTime = TimeOfDay(6, 0)).withDosesPerDay(5)
        assertEquals(288, d.intervalMinutes)
        assertEquals(
            listOf("06:00", "10:48", "15:36", "20:24", "01:12"),
            d.times.map { it.toString() },
        )
    }

    @Test
    fun interval_chip_sets_doses_per_day() {
        val d = MedDraft().withIntervalHours(6)
        assertEquals(4, d.timesPerDay)
        assertEquals(listOf("08:00", "14:00", "20:00", "02:00"), d.times.map { it.toString() })
    }

    @Test
    fun editing_one_time_keeps_the_rule_and_shifts_all() {
        val d = MedDraft().withDosesPerDay(3) // 08 / 16 / 00
        val edited = d.withEditedTime(old = TimeOfDay(16, 0), new = TimeOfDay(15, 0))
        assertEquals(listOf("07:00", "15:00", "23:00"), edited.times.map { it.toString() })
        assertEquals(TimeOfDay(7, 0), edited.firstTime)
        assertEquals(480, edited.doseIntervalMinutes)
    }

    @Test
    fun free_mode_edits_only_that_time_and_persists_no_rule() {
        val d = MedDraft(times = listOf(TimeOfDay(8, 0), TimeOfDay(14, 0))).withAutoSchedule(false)
        val edited = d.withEditedTime(TimeOfDay(14, 0), TimeOfDay(13, 0))
        assertEquals(listOf("08:00", "13:00"), edited.times.map { it.toString() })
        assertEquals(null, edited.doseIntervalMinutes)
    }

    @Test
    fun turning_auto_back_on_rebuilds_rule_from_current_times() {
        val d = MedDraft(autoSchedule = false, times = listOf(TimeOfDay(9, 0), TimeOfDay(13, 0)))
            .withAutoSchedule(true)
        assertEquals(2, d.timesPerDay)
        assertEquals(listOf("09:00", "21:00"), d.times.map { it.toString() })
    }

    @Test
    fun from_existing_restores_the_interval_rule() {
        val group = ScheduleGroupEntity(
            medicineId = 1, scheduleType = "DAILY",
            consumptionTimes = "00:00,08:00,16:00", startDateEpochDay = 0,
            doseIntervalMinutes = 480,
        )
        val d = MedDraft.fromExisting(MedicineEntity(name = "Amoxicillin"), group)
        assertTrue(d.autoSchedule)
        assertEquals(3, d.timesPerDay)
        assertEquals(TimeOfDay(8, 0), d.firstTime) // the day starts at 08:00, not midnight
    }

    @Test
    fun from_existing_without_rule_is_free_mode() {
        val group = ScheduleGroupEntity(
            medicineId = 1, scheduleType = "DAILY",
            consumptionTimes = "08:00,14:00", startDateEpochDay = 0,
        )
        assertEquals(false, MedDraft.fromExisting(MedicineEntity(name = "X"), group).autoSchedule)
    }

    // ---- variable dose / refill extras ----

    @Test
    fun ramp_preview_lists_each_change_until_the_stop() {
        val d = MedDraft(dosageQty = "40", rampEnabled = true, rampDown = true, rampAmount = "10", rampEveryDays = 7, rampFinal = "0")
        val start = LocalDate.of(2026, 1, 1)
        assertEquals(
            listOf(start to 40f, start.plusDays(7) to 30f, start.plusDays(14) to 20f, start.plusDays(21) to 10f, start.plusDays(28) to 0f),
            d.rampPreview(start.toEpochDay()),
        )
    }

    @Test
    fun ramp_in_the_wrong_direction_is_rejected() {
        val d = MedDraft(dosageQty = "2", rampEnabled = true, rampDown = true, rampAmount = "1", rampFinal = "5")
        assertNull(d.doseRamp())
        assertTrue(d.rampPreview().isEmpty())
    }

    @Test
    fun extras_round_trip_through_entities() {
        val d = MedDraft(
            name = "Pred", dosageQty = "3", stockCount = "20", unitsPerPackage = "30",
            refillTime = TimeOfDay(18, 30),
            rampEnabled = true, rampDown = true, rampAmount = "1", rampEveryDays = 2, rampFinal = "0",
        )
        val e = d.scheduleExtras()
        assertEquals(-1f, e.doseStep)
        val group = ScheduleGroupEntity(
            medicineId = 1, dosageValue = 3f, scheduleType = "DAILY", consumptionTimes = "08:00",
            startDateEpochDay = 0, doseStep = e.doseStep, doseStepEveryDays = e.doseStepEveryDays, doseFinal = e.doseFinal,
        )
        val back = MedDraft.fromExisting(d.toMedicine(), group)
        assertTrue(back.rampEnabled)
        assertEquals("1", back.rampAmount)
        assertEquals(2, back.rampEveryDays)
        assertEquals("30", back.unitsPerPackage)
        assertEquals(TimeOfDay(18, 30), back.refillTime)
    }

    @Test
    fun reminder_sound_survives_edit_round_trip() {
        val med = MedicineEntity(id = 1, name = "A", reminderSound = "medwi:bell")
        val group = ScheduleGroupEntity(
            id = 2, medicineId = 1, scheduleType = "DAILY", consumptionTimes = "08:00", startDateEpochDay = 100,
        )
        val d = MedDraft.fromExisting(med, group)
        assertEquals("medwi:bell", d.reminderSound)
        assertEquals("medwi:bell", d.toMedicine().reminderSound)
        assertEquals(null, MedDraft(name = "B").toMedicine().reminderSound)
    }
}
