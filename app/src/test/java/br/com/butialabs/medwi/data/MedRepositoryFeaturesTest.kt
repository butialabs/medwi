package br.com.butialabs.medwi.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import br.com.butialabs.medwi.alarm.AlarmGateway
import br.com.butialabs.medwi.alarm.Notifications
import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.core.scheduler.Scheduler
import br.com.butialabs.medwi.data.backup.BackupManager
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.TreatmentState
import kotlinx.coroutines.flow.first
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * v4 features: take all, real taken time, dose edits, pause/resume/end, stock & refill,
 * variable dose, backup round trip.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MedRepositoryFeaturesTest {

    private class FakeAlarm : AlarmGateway {
        var refillAt: Long? = null
        var refillCancelled = false
        override fun scheduleExact(itemId: Long, triggerAtMillis: Long) {}
        override fun cancel() {}
        override fun scheduleRefill(triggerAtMillis: Long) { refillAt = triggerAtMillis; refillCancelled = false }
        override fun cancelRefill() { refillAt = null; refillCancelled = true }
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: MedRepository
    private val alarm = FakeAlarm()
    private var changes = 0

    private val zone = ZoneOffset.UTC
    private val day0 = LocalDate.of(2026, 1, 1)
    private val now = day0.atStartOfDay(zone).toInstant().toEpochMilli()
    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        repo = MedRepository(ctx, db, Scheduler(zone), alarm, { changes++ }, zone)
    }

    @After
    fun tearDown() = db.close()

    private fun daily(vararg hours: Int) = Schedule(
        type = ScheduleType.DAILY,
        consumptionTimes = hours.map { ConsumptionTime(it, 0) },
        startDateEpochDay = day0.toEpochDay(),
    )

    private suspend fun add(
        name: String,
        hour: Int = 8,
        stock: Int? = null,
        threshold: Int? = null,
        box: Int? = null,
        extras: ScheduleExtras = ScheduleExtras(),
        dose: Float = 1f,
    ): Long = repo.addMedication(
        medicine = MedicineEntity(name = name, stockCount = stock, refillThreshold = threshold, unitsPerPackage = box),
        dosageValue = dose, dosageUnit = "pill",
        schedule = daily(hour), nowMillis = now, extras = extras,
    )

    private suspend fun medId(groupId: Long) = db.scheduleGroupDao().byId(groupId)!!.medicineId

    // ---- take all / real time / dose edits ----

    private fun shownNotificationIds(): List<Int> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ctx.getSystemService(android.app.NotificationManager::class.java).activeNotifications.map { it.id }
    }

    @Test
    fun a_shared_time_slot_gets_one_notification_that_shrinks_as_doses_are_answered() = runTest {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        add("A"); add("B"); add("C")
        val dueAt = at(day0, 8)
        val slot = repo.awaitingAt(dueAt)
        assertEquals(3, slot.size)

        repo.showReminders(dueAt, slot, silent = false)
        assertEquals(listOf(Notifications.slotNotificationId(dueAt)), shownNotificationIds())

        // Two left: still one combined notification.
        repo.markDose(slot[0].id, DoseStatus.TAKEN, nowMillis = at(day0, 8, 1))
        assertEquals(listOf(Notifications.slotNotificationId(dueAt)), shownNotificationIds())

        // One left: becomes that dose's own notification.
        repo.snooze(slot[1].id, nowMillis = at(day0, 8, 2))
        assertEquals(listOf(Notifications.notificationId(slot[2].id)), shownNotificationIds())

        repo.markDose(slot[2].id, DoseStatus.SKIPPED, nowMillis = at(day0, 8, 3))
        assertTrue(shownNotificationIds().isEmpty())
    }

    @Test
    fun snoozeAll_moves_the_slot_to_one_shared_time() = runTest {
        add("A"); add("B")
        val ids = repo.awaitingAt(at(day0, 8)).map { it.id }
        repo.snoozeAll(ids, minutes = 15, nowMillis = at(day0, 8, 1))
        assertEquals(ids.toSet(), db.doseItemDao().dueAt(at(day0, 8, 16)).map { it.id }.toSet())
    }

    @Test
    fun markDoses_takes_every_dose_of_a_slot() = runTest {
        val a = add("A")
        val b = add("B")
        val slot = db.doseItemDao().dueAt(at(day0, 8))
        assertEquals(2, slot.size)

        assertEquals(2, repo.markDoses(slot.map { it.id }, DoseStatus.TAKEN, nowMillis = at(day0, 8, 5)))

        listOf(a, b).forEach { g ->
            val dose = db.doseItemDao().byKey(g, at(day0, 8))!!
            assertEquals("TAKEN", dose.status)
            assertEquals(at(day0, 8, 5), dose.actualAtMillis)
        }
        assertTrue(changes > 0)
    }

    @Test
    fun take_at_records_the_real_time() = runTest {
        val g = add("A")
        val dose = db.doseItemDao().byKey(g, at(day0, 8))!!

        repo.markDose(dose.id, DoseStatus.TAKEN, nowMillis = at(day0, 10), takenAtMillis = at(day0, 7, 40))

        assertEquals(at(day0, 7, 40), repo.doseById(dose.id)!!.actualAtMillis)
    }

    @Test
    fun updateDose_changes_quantity_and_note_and_moves_stock_for_taken_dose() = runTest {
        val g = add("A", stock = 10)
        val dose = db.doseItemDao().byKey(g, at(day0, 8))!!
        repo.markDose(dose.id, DoseStatus.TAKEN, nowMillis = at(day0, 8))
        assertEquals(9, db.medicineDao().byId(medId(g))!!.stockCount)

        repo.updateDose(dose.id, dosageValue = 2f, note = "with food")

        val edited = repo.doseById(dose.id)!!
        assertEquals(2f, edited.dosageValue)
        assertEquals("with food", edited.note)
        assertEquals(8, db.medicineDao().byId(medId(g))!!.stockCount)
        // only this dose changed
        assertEquals(1f, db.doseItemDao().byKey(g, at(day0.plusDays(1), 8))!!.dosageValue)

        repo.updateDose(dose.id, note = "")
        assertNull(repo.doseById(dose.id)!!.note)
    }

    // ---- pause / resume / end ----

    @Test
    fun pause_drops_future_doses_keeps_history_and_resume_brings_them_back() = runTest {
        val g = add("A")
        val first = db.doseItemDao().byKey(g, at(day0, 8))!!
        repo.markDose(first.id, DoseStatus.TAKEN, nowMillis = at(day0, 8))
        val later = at(day0, 12)

        repo.pause(g, nowMillis = later)

        val afterPause = db.doseItemDao().forGroup(g)
        assertEquals(listOf("TAKEN"), afterPause.map { it.status })
        val item = repo.observeMedList().first().single()
        assertEquals(TreatmentState.PAUSED, item.state)

        repo.resume(g, nowMillis = later)

        assertTrue(db.doseItemDao().forGroup(g).size > 30)
        assertEquals(TreatmentState.ACTIVE, repo.observeMedList().first().single().state)
        assertEquals(first.id, db.doseItemDao().byKey(g, at(day0, 8))!!.id) // history intact
    }

    @Test
    fun end_stops_doses_and_lists_as_ended() = runTest {
        val g = add("A")
        repo.end(g, nowMillis = at(day0, 12))

        assertTrue(db.doseItemDao().forGroup(g).none { it.scheduledAtMillis > at(day0, 12) })
        val group = db.scheduleGroupDao().byId(g)!!
        assertEquals(day0.toEpochDay(), group.endDateEpochDay)
        assertEquals(TreatmentState.ENDED, repo.observeMedList().first().single().state)

        repo.regenerateAll(nowMillis = at(day0, 13)) // boot must not resurrect it
        assertTrue(db.doseItemDao().forGroup(g).none { it.scheduledAtMillis > at(day0, 12) })
    }

    @Test
    fun adding_a_med_later_in_the_day_does_not_create_past_doses() = runTest {
        val g = repo.addMedication(
            medicine = MedicineEntity(name = "Late"),
            dosageValue = 1f, dosageUnit = "pill",
            schedule = daily(8, 20), nowMillis = at(day0, 10),
        )
        assertNull(db.doseItemDao().byKey(g, at(day0, 8)))
        assertNotNull(db.doseItemDao().byKey(g, at(day0, 20)))
    }

    // ---- stock / refill ----

    @Test
    fun addStock_logs_refill_and_estimate_walks_future_doses() = runTest {
        val g = add("A", stock = 3, threshold = 5, box = 30)
        val med = medId(g)
        // 3 left, 1/day from Jan 1 -> the 4th dose (Jan 4) is the first without stock
        assertEquals(day0.plusDays(3), repo.estimateRunOut(med, nowMillis = now))
        assertNotNull("low stock arms the daily refill reminder", alarm.refillAt)
        assertEquals(at(day0, 9), alarm.refillAt)

        repo.addStock(med, 30, nowMillis = now)

        assertEquals(33, db.medicineDao().byId(med)!!.stockCount)
        val history = repo.observeRefills(med).first()
        assertEquals(listOf(30 to 33), history.map { it.amount to it.resultingStock })
        assertTrue("stock back above threshold -> reminder off", alarm.refillCancelled)
    }

    @Test
    fun setStock_logs_the_adjustment() = runTest {
        val g = add("A", stock = 10)
        val med = medId(g)
        repo.setStock(med, 4, nowMillis = now)
        assertEquals(4, db.medicineDao().byId(med)!!.stockCount)
        assertEquals(-6, repo.observeRefills(med).first().single().amount)
    }

    // ---- variable dose ----

    @Test
    fun taper_is_persisted_and_generates_decreasing_doses() = runTest {
        val g = add(
            "Prednisone", dose = 3f,
            extras = ScheduleExtras(doseStep = -1f, doseStepEveryDays = 2, doseFinal = 0f),
        )
        val doses = db.doseItemDao().forGroup(g).sortedBy { it.scheduledAtMillis }.map { it.dosageValue }
        assertEquals(listOf(3f, 3f, 2f, 2f, 1f, 1f), doses)
    }

    // ---- backup ----

    @Test
    fun backup_round_trip_restores_everything() = runTest {
        val g = add("A", stock = 10, box = 20)
        val med = medId(g)
        val first = db.doseItemDao().byKey(g, at(day0, 8))!!
        repo.markDose(first.id, DoseStatus.TAKEN, nowMillis = at(day0, 8))
        repo.updateDose(first.id, note = "ok")
        repo.addStock(med, 20, nowMillis = now)
        repo.setMedicineSound(med, "medwi:bell")
        var general: String? = "medwi:chime"
        val backup = BackupManager(db, repo, object : BackupManager.SoundSetting {
            override fun get() = general
            override fun set(key: String) { general = key }
        })

        val out = ByteArrayOutputStream()
        backup.export(out, nowMillis = now, appVersion = "test")
        repo.deleteMedicine(med, nowMillis = now)
        assertTrue(db.medicineDao().all().isEmpty())

        val parsed = backup.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, parsed.medicines.size)
        assertEquals("medwi:chime", parsed.reminderSound)
        general = null
        backup.restore(parsed, nowMillis = now)
        assertEquals("medwi:chime", general)
        assertEquals("medwi:bell", db.medicineDao().byId(med)!!.reminderSound)

        assertEquals("A", db.medicineDao().byId(med)!!.name)
        val restored = db.doseItemDao().byKey(g, at(day0, 8))!!
        assertEquals("TAKEN", restored.status)
        assertEquals("ok", restored.note)
        assertEquals(1, repo.observeRefills(med).first().size)
    }
}
