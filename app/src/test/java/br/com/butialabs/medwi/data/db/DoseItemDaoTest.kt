package br.com.butialabs.medwi.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Integration tests for [DoseItemDao] against an in-memory Room database.
 * Exercises the raw SQL that pure-JVM tests can't reach: the three-table JOIN in
 * [DoseItemDao.observeViewsBetween], the [DoseItemDao.nextPending] filter, and the
 * future un-answered selection in [DoseItemDao.futureUnanswered].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class DoseItemDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var medDao: MedicineDao
    private lateinit var groupDao: ScheduleGroupDao
    private lateinit var doseDao: DoseItemDao

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        medDao = db.medicineDao()
        groupDao = db.scheduleGroupDao()
        doseDao = db.doseItemDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newGroup(medName: String, strength: String?): Long {
        val medId = medDao.insert(
            MedicineEntity(name = medName, form = "tablet", strengthValue = strength, strengthUnit = "mg"),
        )
        return groupDao.insert(
            ScheduleGroupEntity(
                medicineId = medId,
                scheduleType = "DAILY",
                consumptionTimes = "08:00",
                startDateEpochDay = 0,
            ),
        )
    }

    private fun dose(groupId: Long, at: Long, status: String) = DoseItemEntity(
        groupId = groupId,
        scheduledAtMillis = at,
        dosageValue = 1f,
        dosageUnit = "pill",
        status = status,
    )

    @Test
    fun observeViewsBetween_joins_medicine_and_filters_and_orders_by_time() = runTest {
        val g = newGroup("Aspirin", "100")
        doseDao.upsertAll(
            listOf(
                dose(g, at = 3000, status = "PENDING"), // out of range (upper)
                dose(g, at = 1000, status = "PENDING"),
                dose(g, at = 2000, status = "TAKEN"),
            ),
        )

        val views = doseDao.observeViewsBetween(from = 1000, to = 2000).first()

        assertEquals(2, views.size)
        // ordered by scheduled time
        assertEquals(1000L, views[0].scheduledAtMillis)
        assertEquals(2000L, views[1].scheduledAtMillis)
        // joined medicine columns
        assertEquals("Aspirin", views[0].medicineName)
        assertEquals("100", views[0].strengthValue)
        assertEquals("mg", views[0].strengthUnit)
        // status carried through
        assertEquals("PENDING", views[0].status)
        assertEquals("TAKEN", views[1].status)
    }

    @Test
    fun nextPending_returns_earliest_future_pending_skipping_done_and_past() = runTest {
        val g = newGroup("Metformin", "500")
        doseDao.upsertAll(
            listOf(
                dose(g, at = 1000, status = "PENDING"), // in the past (<= after)
                dose(g, at = 2000, status = "TAKEN"),   // future but already taken
                dose(g, at = 3000, status = "PENDING"), // <-- expected
                dose(g, at = 4000, status = "PENDING"),
            ),
        )

        val next = doseDao.nextPending(after = 1500)

        assertEquals(3000L, next?.scheduledAtMillis)
    }

    @Test
    fun nextPending_null_when_none_remaining() = runTest {
        val g = newGroup("Vitamin D", null)
        doseDao.upsertAll(listOf(dose(g, at = 1000, status = "TAKEN")))

        assertNull(doseDao.nextPending(after = 0))
    }

    @Test
    fun futureUnanswered_selects_only_future_open_doses_of_that_group() = runTest {
        val a = newGroup("DrugA", null)
        val b = newGroup("DrugB", null)
        doseDao.upsertAll(
            listOf(
                dose(a, at = 1000, status = "PENDING"), // past -> keep
                dose(a, at = 2000, status = "TAKEN"),   // future but taken -> keep
                dose(a, at = 3000, status = "PENDING"), // future pending -> selected
                dose(b, at = 3000, status = "PENDING"), // other group -> not selected
            ),
        )

        val future = doseDao.futureUnanswered(groupId = a, after = 1500)

        assertEquals(listOf(3000L), future.map { it.scheduledAtMillis })
    }
}
