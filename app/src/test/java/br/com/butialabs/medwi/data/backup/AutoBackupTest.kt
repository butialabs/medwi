package br.com.butialabs.medwi.data.backup

import android.content.Context
import android.net.Uri
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import br.com.butialabs.medwi.alarm.AlarmGateway
import br.com.butialabs.medwi.core.scheduler.Scheduler
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.db.MedicineEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AutoBackupTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver()).allowMainThreadQueries().build()
        val alarms = object : AlarmGateway {
            override fun scheduleExact(itemId: Long, triggerAtMillis: Long) {}
            override fun cancel() {}
        }
        backup = BackupManager(db, MedRepository(ctx, db, Scheduler(ZoneOffset.UTC), alarms))
    }

    @After
    fun tearDown() {
        AutoBackup.disable(ctx)
        db.close()
    }

    @Test
    fun writes_the_chosen_file_and_records_the_time() = runTest {
        db.medicineDao().insert(MedicineEntity(name = "Euthyrox"))
        val file = File(ctx.cacheDir, "auto.json").apply { delete() }
        AutoBackup.enable(ctx, Uri.fromFile(file))

        assertTrue(AutoBackup.writeOnce(ctx, backup, nowMillis = 1234L))

        assertTrue(file.readText().contains("Euthyrox"))
        val status = AutoBackup.status(ctx)
        assertEquals(1234L, status.lastAtMillis)
        assertEquals(null, status.lastError)
        // the file is a normal backup: it can be read back
        assertEquals(1, backup.read(file.inputStream()).medicines.size)
    }

    @Test
    fun unreachable_file_records_an_error() = runTest {
        AutoBackup.enable(ctx, Uri.parse("content://nobody.home/backup.json"))

        assertFalse(AutoBackup.writeOnce(ctx, backup))
        assertNotNull(AutoBackup.status(ctx).lastError)
    }

    @Test
    fun disabled_does_nothing() = runTest {
        assertFalse(AutoBackup.writeOnce(ctx, backup))
        assertFalse(AutoBackup.status(ctx).enabled)
    }
}
