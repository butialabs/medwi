package br.com.butialabs.medwi

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import br.com.butialabs.medwi.alarm.Notifications
import br.com.butialabs.medwi.data.backup.AutoBackup
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/** Hilt application entry point. Singletons are provided by `di/AppModule`. */
@HiltAndroidApp
class MedwiApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        AutoBackup.schedule(this)
    }
}
