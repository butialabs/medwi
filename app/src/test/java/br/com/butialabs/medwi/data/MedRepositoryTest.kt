package br.com.butialabs.medwi.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import br.com.butialabs.medwi.alarm.AlarmGateway
import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.core.scheduler.Scheduler
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.ui.addmed.FrequencyChoice
import br.com.butialabs.medwi.ui.addmed.MedDraft
import br.com.butialabs.medwi.ui.addmed.TimeOfDay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Integration tests for [MedRepository]: wiring of scheduler + DAOs + alarm.
 * Uses an in-memory Room DB, a UTC scheduler for determinism, and a fake alarm
 * gateway that records what the repository armed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MedRepositoryTest {

    private class FakeAlarm : AlarmGateway {
        var lastItemId: Long? = null
        var lastTriggerAt: Long? = null
        var cancelled = false
        val nagsCancelled = mutableListOf<Long>()
        override fun scheduleExact(itemId: Long, triggerAtMillis: Long) {
            lastItemId = itemId; lastTriggerAt = triggerAtMillis; cancelled = false
        }
        override fun cancel() { cancelled = true }
        override fun cancelNag(itemId: Long) { nagsCancelled += itemId }
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: MedRepository
    private val fakeAlarm = FakeAlarm()

    private val zone = ZoneOffset.UTC
    private val day0 = LocalDate.of(2026, 1, 1)
    private val now = day0.atStartOfDay(zone).toInstant().toEpochMilli() // Jan 1 00:00 UTC
    private fun at(date: LocalDate, hour: Int) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        repo = MedRepository(ctx, db, Scheduler(zone), fakeAlarm)
    }

    @After
    fun tearDown() = db.close()

    private fun daily(hour: Int) = Schedule(
        type = ScheduleType.DAILY,
        consumptionTimes = listOf(ConsumptionTime(hour, 0)),
        startDateEpochDay = day0.toEpochDay(),
    )

    private suspend fun addDailyAt8(): Long = repo.addMedication(
        medicine = MedicineEntity(name = "Aspirin", strengthValue = "100", strengthUnit = "mg"),
        dosageValue = 1f, dosageUnit = "pill",
        schedule = daily(8), nowMillis = now,
    )

    @Test
    fun addMedication_materializes_window_and_arms_first_dose() = runTest {
        val groupId = addDailyAt8()

        val items = db.doseItemDao().forGroup(groupId)
        assertTrue("expected ~32 doses, got ${items.size}", items.size in 31..34)
        // first future dose = Jan 1 08:00
        assertEquals(at(day0, 8), fakeAlarm.lastTriggerAt)
        assertNotNull(fakeAlarm.lastItemId)
    }

    @Test
    fun markDose_taken_sets_status_and_actual_and_rearms_to_next_day() = runTest {
        addDailyAt8()
        val first = db.doseItemDao().nextPending(now)!! // Jan 1 08:00

        repo.markDose(first.id, DoseStatus.TAKEN, nowMillis = now)

        val reloaded = repo.doseById(first.id)!!
        assertEquals("TAKEN", reloaded.status)
        assertEquals(now, reloaded.actualAtMillis)
        // alarm now armed for the following day's dose
        assertEquals(at(day0.plusDays(1), 8), fakeAlarm.lastTriggerAt)
    }

    @Test
    fun snooze_keeps_planned_time_increments_counter_and_rearms_to_snoozed_time() = runTest {
        addDailyAt8()
        val first = db.doseItemDao().nextPending(now)!!

        repo.snooze(first.id, minutes = 15, nowMillis = now)

        val reloaded = repo.doseById(first.id)!!
        assertEquals("SNOOZED", reloaded.status)
        // the planned time is the dose's identity and must not move
        assertEquals(first.scheduledAtMillis, reloaded.scheduledAtMillis)
        assertEquals(now + 15 * 60_000L, reloaded.snoozedUntilMillis)
        assertEquals(1, reloaded.snoozeCounter)
        assertTrue("answered dose drops its nag", first.id in fakeAlarm.nagsCancelled)
        // snoozed dose is the soonest upcoming -> alarm re-armed to it
        assertEquals(now + 15 * 60_000L, fakeAlarm.lastTriggerAt)
        assertEquals(first.id, fakeAlarm.lastItemId)
    }

    @Test
    fun updateMedication_rewrites_schedule_and_regenerates() = runTest {
        val groupId = addDailyAt8()
        val medId = db.scheduleGroupDao().byId(groupId)!!.medicineId

        // Edit: rename + go from once to twice a day.
        val draft = MedDraft(
            name = "Aspirin PM",
            frequency = FrequencyChoice.EVERY_DAY,
            times = listOf(TimeOfDay(8, 0), TimeOfDay(20, 0)),
        )
        repo.updateMedication(
            medicineId = medId,
            groupId = groupId,
            medicine = draft.toMedicine(),
            dosageValue = draft.dosageValue,
            dosageUnit = draft.formToken(),
            schedule = draft.toSchedule(startEpochDay = day0.toEpochDay()),
            nowMillis = now,
        )

        assertEquals("Aspirin PM", db.medicineDao().byId(medId)!!.name)
        assertEquals("08:00,20:00", db.scheduleGroupDao().byId(groupId)!!.consumptionTimes)
        // two doses per day now materialized
        assertTrue(db.doseItemDao().forGroup(groupId).size > 40)
    }

    @Test
    fun logExtraDose_records_a_taken_dose_now() = runTest {
        // An as-needed medication generates no scheduled doses.
        val groupId = repo.addMedication(
            medicine = MedicineEntity(name = "Ibuprofen", stockCount = 10, refillThreshold = 2),
            dosageValue = 1f, dosageUnit = "tablet",
            schedule = Schedule(ScheduleType.AS_NEEDED, emptyList(), day0.toEpochDay()),
            nowMillis = now,
        )
        assertTrue(db.doseItemDao().forGroup(groupId).isEmpty())

        repo.logExtraDose(groupId, nowMillis = now)

        val doses = db.doseItemDao().forGroup(groupId)
        assertEquals(1, doses.size)
        assertEquals("TAKEN", doses[0].status)
        assertEquals(now, doses[0].scheduledAtMillis)
        // stock decremented from 10 -> 9
        val medId = db.scheduleGroupDao().byId(groupId)!!.medicineId
        assertEquals(9, db.medicineDao().byId(medId)!!.stockCount)
    }

    @Test
    fun deleteMedicine_cascades_doses_and_cancels_alarm() = runTest {
        val groupId = addDailyAt8()
        val medId = db.scheduleGroupDao().byId(groupId)!!.medicineId
        assertTrue(db.doseItemDao().forGroup(groupId).isNotEmpty())

        repo.deleteMedicine(medId, nowMillis = now)

        assertNull(db.medicineDao().byId(medId))
        assertTrue("doses should cascade-delete", db.doseItemDao().forGroup(groupId).isEmpty())
        assertTrue("no pending doses left -> alarm cancelled", fakeAlarm.cancelled)
    }

    @Test
    fun regenerate_preserves_taken_status_across_runs() = runTest {
        val groupId = addDailyAt8()
        val first = db.doseItemDao().nextPending(now)!!
        repo.markDose(first.id, DoseStatus.TAKEN, nowMillis = now)

        repo.regenerate(groupId, nowMillis = now)

        // the taken dose is still TAKEN and was not resurrected as PENDING
        val taken = db.doseItemDao().forGroup(groupId)
            .filter { it.status == "TAKEN" }
        assertEquals(1, taken.size)
        assertEquals(at(day0, 8), taken.first().scheduledAtMillis)
    }

    // ---- notification "Take" regression: dose ids must survive regeneration ----

    @Test
    fun regenerate_keeps_ids_of_pending_doses() = runTest {
        val groupId = addDailyAt8()
        val before = db.doseItemDao().forGroup(groupId).associate { it.scheduledAtMillis to it.id }

        repo.regenerate(groupId, nowMillis = now)
        repo.regenerateAll(nowMillis = now) // what reboot / app update triggers

        val after = db.doseItemDao().forGroup(groupId).associate { it.scheduledAtMillis to it.id }
        assertEquals(before, after)
    }

    @Test
    fun take_from_a_notification_posted_before_a_regenerate_still_marks_the_dose() = runTest {
        val groupId = addDailyAt8()
        val notified = db.doseItemDao().nextPending(now)!! // id carried by the notification

        repo.regenerateAll(nowMillis = now) // e.g. the APK was reinstalled meanwhile

        val resolved = repo.resolveDose(notified.id, notified.groupId, notified.scheduledAtMillis)!!
        assertTrue(repo.markDose(resolved.id, DoseStatus.TAKEN, nowMillis = now))
        val taken = db.doseItemDao().forGroup(groupId).filter { it.status == "TAKEN" }
        assertEquals(listOf(notified.scheduledAtMillis), taken.map { it.scheduledAtMillis })
    }

    @Test
    fun resolveDose_falls_back_to_group_and_planned_time() = runTest {
        val groupId = addDailyAt8()
        val dose = db.doseItemDao().nextPending(now)!!

        val resolved = repo.resolveDose(itemId = 999_999, groupId = groupId, scheduledAtMillis = dose.scheduledAtMillis)

        assertEquals(dose.id, resolved?.id)
    }

    @Test
    fun markDose_reports_missing_dose() = runTest {
        assertEquals(false, repo.markDose(424242, DoseStatus.TAKEN, nowMillis = now))
    }

    @Test
    fun snooze_then_regenerate_does_not_duplicate_the_dose() = runTest {
        val groupId = addDailyAt8()
        val first = db.doseItemDao().nextPending(now)!!
        val countBefore = db.doseItemDao().forGroup(groupId).size

        repo.snooze(first.id, minutes = 15, nowMillis = now)
        repo.regenerate(groupId, nowMillis = now)

        val doses = db.doseItemDao().forGroup(groupId)
        assertEquals(countBefore, doses.size)
        assertEquals(1, doses.count { it.scheduledAtMillis == first.scheduledAtMillis })
        assertEquals("SNOOZED", doses.first { it.id == first.id }.status)
    }

    // ---- Progress history corrections ----

    @Test
    fun retroactive_take_records_planned_time_and_undo_restores_stock() = runTest {
        val groupId = repo.addMedication(
            medicine = MedicineEntity(name = "Aspirin", stockCount = 10),
            dosageValue = 1f, dosageUnit = "pill",
            schedule = daily(8), nowMillis = now,
        )
        val medId = db.scheduleGroupDao().byId(groupId)!!.medicineId
        val dose = db.doseItemDao().nextPending(now)!!
        val later = dose.scheduledAtMillis + 3 * Scheduler.ONE_DAY_MILLIS

        repo.markDose(dose.id, DoseStatus.TAKEN, nowMillis = later, retroactive = true)
        assertEquals(dose.scheduledAtMillis, repo.doseById(dose.id)!!.actualAtMillis)
        assertEquals(9, db.medicineDao().byId(medId)!!.stockCount)

        repo.markDose(dose.id, DoseStatus.PENDING, nowMillis = later)
        val undone = repo.doseById(dose.id)!!
        assertEquals("PENDING", undone.status)
        assertNull(undone.actualAtMillis)
        assertEquals(10, db.medicineDao().byId(medId)!!.stockCount)
    }

    @Test
    fun interval_rule_is_persisted_with_the_schedule() = runTest {
        val groupId = repo.addMedication(
            medicine = MedicineEntity(name = "Amoxicillin"),
            dosageValue = 1f, dosageUnit = "pill",
            schedule = daily(8), nowMillis = now,
            extras = ScheduleExtras(doseIntervalMinutes = 8 * 60),
        )
        assertEquals(480, db.scheduleGroupDao().byId(groupId)!!.doseIntervalMinutes)
    }
}
