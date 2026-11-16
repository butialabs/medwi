package br.com.butialabs.medwi.di

import android.content.Context
import br.com.butialabs.medwi.alarm.AlarmGateway
import br.com.butialabs.medwi.alarm.AlarmScheduler
import br.com.butialabs.medwi.core.scheduler.Scheduler
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.backup.BackupManager
import br.com.butialabs.medwi.widget.WidgetUpdater
import br.com.butialabs.medwi.data.catalog.MedCatalog
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.reminders.ReminderContent
import br.com.butialabs.medwi.data.settings.AppSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.ZoneId
import javax.inject.Singleton

/** Application-wide singletons, replacing the old manual DI in MedwiApp. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        AppDatabase.get(context)

    @Provides
    @Singleton
    fun provideScheduler(): Scheduler = Scheduler(ZoneId.systemDefault())

    @Provides
    @Singleton
    fun provideAlarmGateway(@ApplicationContext context: Context): AlarmGateway =
        AlarmScheduler(context)

    @Provides
    @Singleton
    fun provideRepository(
        @ApplicationContext context: Context,
        db: AppDatabase,
        scheduler: Scheduler,
        alarms: AlarmGateway,
        reminderContent: ReminderContent,
    ): MedRepository = MedRepository(
        context, db, scheduler, alarms, WidgetUpdater(context), reminderContent = reminderContent,
    )

    @Provides
    @Singleton
    fun provideBackupManager(
        @ApplicationContext context: Context,
        db: AppDatabase,
        repository: MedRepository,
    ): BackupManager = BackupManager(
        db, repository,
        object : BackupManager.SoundSetting {
            override fun get(): String? =
                if (AppSettings.hasReminderSound(context)) AppSettings.reminderSound(context) else null
            override fun set(key: String) = AppSettings.setReminderSound(context, key)
        },
    )

    @Provides
    @Singleton
    fun provideCatalog(@ApplicationContext context: Context): MedCatalog = MedCatalog(context)

    @Provides
    @Singleton
    fun provideReminderContent(@ApplicationContext context: Context): ReminderContent =
        ReminderContent(context)
}
