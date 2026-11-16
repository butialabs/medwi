package br.com.butialabs.medwi.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Database(
    entities = [
        MedicineEntity::class,
        ScheduleGroupEntity::class,
        DoseItemEntity::class,
        RefillEventEntity::class,
    ],
    // v2: added ScheduleGroup.everyXMonths + Medicine.iconKey/instructions/stock/refill.
    // Pre-release app with no real users -> fallbackToDestructiveMigration recreates the DB.
    // v3: DoseItem.snoozedUntilMillis (snooze no longer rewrites the planned time) +
    //     ScheduleGroup.doseIntervalMinutes (interval-based time organizer).
    // v4: pause/end, variable dose, dose notes, refill box size/reminder + refill_event.
    //     From v3 on, migrations are real: user data (and backups) must survive updates.
    // v5: medicine.color2 (two-tone pill drawings); dose units become real units
    //     (pill, ml, drop...) instead of form tokens; legacy icon keys -> pill shapes.
    // v6: drop the old avatar background colors (brand green etc.): they are not pill
    //     colors, so drawings fall back to their natural default (white pill, amber bottle).
    // v7: medicine.reminderSound (per-medicine reminder sound; null = general sound).
    version = 7,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao
    abstract fun scheduleGroupDao(): ScheduleGroupDao
    abstract fun doseItemDao(): DoseItemDao
    abstract fun refillEventDao(): RefillEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medwi.db",
                )
                    .setDriver(BundledSQLiteDriver())
                    .addMigrations(*MIGRATIONS)
                    // Only pre-release schemas (no real users) may still be wiped.
                    .fallbackToDestructiveMigrationFrom(true, 1, 2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

/** v3 -> v4: additive columns + refill history table. */
val MIGRATION_3_4 = Migration(3, 4) { db ->
    db.execSQL("ALTER TABLE `medicine` ADD COLUMN `unitsPerPackage` INTEGER")
    db.execSQL("ALTER TABLE `medicine` ADD COLUMN `refillReminderTime` TEXT")
    db.execSQL("ALTER TABLE `schedule_group` ADD COLUMN `pausedAtMillis` INTEGER")
    db.execSQL("ALTER TABLE `schedule_group` ADD COLUMN `endedAtMillis` INTEGER")
    db.execSQL("ALTER TABLE `schedule_group` ADD COLUMN `doseStep` REAL")
    db.execSQL("ALTER TABLE `schedule_group` ADD COLUMN `doseStepEveryDays` INTEGER")
    db.execSQL("ALTER TABLE `schedule_group` ADD COLUMN `doseFinal` REAL")
    db.execSQL("ALTER TABLE `dose_item` ADD COLUMN `note` TEXT")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `refill_event` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `medicineId` INTEGER NOT NULL, " +
            "`atMillis` INTEGER NOT NULL, `amount` INTEGER NOT NULL, `resultingStock` INTEGER NOT NULL, " +
            "FOREIGN KEY(`medicineId`) REFERENCES `medicine`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_refill_event_medicineId` ON `refill_event` (`medicineId`)")
}

/** v4 -> v5: second pill color, real dose units, icon keys -> pill shapes. */
val MIGRATION_4_5 = Migration(4, 5) { db ->
    db.execSQL("ALTER TABLE `medicine` ADD COLUMN `color2` TEXT")
    for (table in listOf("schedule_group", "dose_item")) {
        db.execSQL(
            "UPDATE `$table` SET dosageUnit = CASE dosageUnit " +
                "WHEN 'tablet' THEN 'pill' WHEN 'solution' THEN 'ml' WHEN 'drops' THEN 'drop' " +
                "WHEN 'inhaler' THEN 'puff' WHEN 'injection' THEN 'ml' WHEN 'powder' THEN 'sachet' " +
                "WHEN 'other' THEN 'unit' ELSE dosageUnit END"
        )
    }
    db.execSQL(
        "UPDATE `medicine` SET iconKey = CASE iconKey " +
            "WHEN 'pill' THEN 'round' WHEN 'drop' THEN 'dropper' ELSE iconKey END"
    )
}

/** Avatar background colors offered before v5; never real pill colors. */
private val LEGACY_AVATAR_COLORS = listOf(
    "#1A7F37", "#1B6B5A", "#40617E", "#7A5AA6", "#B3261E", "#8C5A00", "#2E6B2E", "#555E6B",
)

/** v5 -> v6: legacy avatar colors -> NULL (the drawing's default color). */
val MIGRATION_5_6 = Migration(5, 6) { db ->
    val list = LEGACY_AVATAR_COLORS.joinToString(",") { "'$it'" }
    db.execSQL("UPDATE `medicine` SET color = NULL WHERE UPPER(color) IN ($list)")
}

/** v6 -> v7: per-medicine reminder sound. */
val MIGRATION_6_7 = Migration(6, 7) { db ->
    db.execSQL("ALTER TABLE `medicine` ADD COLUMN `reminderSound` TEXT")
}

val MIGRATIONS = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
