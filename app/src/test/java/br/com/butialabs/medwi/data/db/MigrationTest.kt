package br.com.butialabs.medwi.data.db

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A database created by the v3 app (schema copied from Room's generated v3 code) must
 * open under v4 with its data intact: from v3 on, migrations are real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MigrationTest {

    private val v3Schema = listOf(
        "CREATE TABLE IF NOT EXISTS `medicine` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `form` TEXT, `strengthValue` TEXT, `strengthUnit` TEXT, `color` TEXT, `shape` TEXT, `foodInstruction` INTEGER NOT NULL, `extId` TEXT, `imageUrl` TEXT, `iconKey` TEXT, `instructions` TEXT, `stockCount` INTEGER, `refillThreshold` INTEGER)",
        "CREATE TABLE IF NOT EXISTS `schedule_group` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicineId` INTEGER NOT NULL, `dosageValue` REAL NOT NULL, `dosageUnit` TEXT NOT NULL, `scheduleType` TEXT NOT NULL, `consumptionTimes` TEXT NOT NULL, `startDateEpochDay` INTEGER NOT NULL, `endDateEpochDay` INTEGER, `everyXDays` INTEGER NOT NULL, `daysOfWeek` TEXT NOT NULL, `cyclePillDays` INTEGER NOT NULL, `cycleBreakDays` INTEGER NOT NULL, `everyXMonths` INTEGER NOT NULL, `doseIntervalMinutes` INTEGER, `active` INTEGER NOT NULL, FOREIGN KEY(`medicineId`) REFERENCES `medicine`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_schedule_group_medicineId` ON `schedule_group` (`medicineId`)",
        "CREATE TABLE IF NOT EXISTS `dose_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` INTEGER NOT NULL, `scheduledAtMillis` INTEGER NOT NULL, `actualAtMillis` INTEGER, `snoozedUntilMillis` INTEGER, `dosageValue` REAL NOT NULL, `dosageUnit` TEXT NOT NULL, `status` TEXT NOT NULL, `snoozeCounter` INTEGER NOT NULL, FOREIGN KEY(`groupId`) REFERENCES `schedule_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_dose_item_groupId` ON `dose_item` (`groupId`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_dose_item_groupId_scheduledAtMillis` ON `dose_item` (`groupId`, `scheduledAtMillis`)",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'e3b69373d2c063296d73c395664497d3')",
    )

    @Test
    fun v3_database_migrates_to_latest_keeping_data() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }

        AndroidSQLiteDriver().open(file.absolutePath).use { db ->
            v3Schema.forEach(db::execSQL)
            db.execSQL("INSERT INTO medicine (id, name, foodInstruction, stockCount, iconKey, color) VALUES (1, 'Aspirin', 0, 12, 'pill', '#1a7f37')")
            db.execSQL(
                "INSERT INTO schedule_group (id, medicineId, dosageValue, dosageUnit, scheduleType, consumptionTimes, " +
                    "startDateEpochDay, everyXDays, daysOfWeek, cyclePillDays, cycleBreakDays, everyXMonths, active) " +
                    "VALUES (1, 1, 1.0, 'tablet', 'DAILY', '08:00', 20454, 1, '', 0, 0, 1, 1)"
            )
            db.execSQL(
                "INSERT INTO dose_item (id, groupId, scheduledAtMillis, actualAtMillis, dosageValue, dosageUnit, status, snoozeCounter) " +
                    "VALUES (7, 1, 1767254400000, 1767254460000, 1.0, 'tablet', 'TAKEN', 0)"
            )
            db.execSQL("PRAGMA user_version = 3")
        }

        val room = Room.databaseBuilder(ctx, AppDatabase::class.java, file.absolutePath)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(*MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val med = room.medicineDao().byId(1)!!
            assertEquals("Aspirin", med.name)
            assertEquals(12, med.stockCount)
            assertNull(med.unitsPerPackage)
            assertNull(med.color2)
            assertNull("old green avatar color must not tint the pill", med.color)
            assertEquals("round", med.iconKey) // v5: generic icon -> pill drawing
            assertNull("v7: existing medicines use the general sound", med.reminderSound)
            room.medicineDao().setReminderSound(1, "medwi:chime")
            assertEquals("medwi:chime", room.medicineDao().byId(1)!!.reminderSound)
            val group = room.scheduleGroupDao().byId(1)!!
            assertNull(group.pausedAtMillis)
            assertNull(group.doseStep)
            assertEquals("pill", group.dosageUnit) // v5: form token -> dose unit
            val dose = room.doseItemDao().byId(7)!!
            assertEquals("TAKEN", dose.status)
            assertNull(dose.note)
            assertEquals("pill", dose.dosageUnit)
            assertEquals(0, room.refillEventDao().all().size)
        } finally {
            room.close()
            file.delete()
        }
    }
}
