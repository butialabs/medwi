package br.com.butialabs.medwi.ui.settings

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.alarm.Notifications

/** One thing that can stop dose reminders from firing, and how to fix it. */
enum class ReliabilityCheck(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
) {
    NOTIFICATIONS(R.string.rel_notifications_title, R.string.rel_notifications_body),
    EXACT_ALARMS(R.string.rel_exact_title, R.string.rel_exact_body),
    BATTERY(R.string.rel_battery_title, R.string.rel_battery_body),
    BACKGROUND(R.string.rel_background_title, R.string.rel_background_body),
    AUTOSTART(R.string.rel_autostart_title, R.string.rel_autostart_body),
}

/** A check's current state. [verifiable] = false means only the user can confirm it. */
data class CheckStatus(val check: ReliabilityCheck, val ok: Boolean, val verifiable: Boolean = true)

/**
 * Inspects the OS settings that decide whether exact dose alarms and their notifications
 * survive Doze, battery savers and OEM "app killers", and builds the intents that open
 * the matching settings screen.
 */
object ReminderReliability {

    private const val PREFS = "reminder_reliability"
    private const val KEY_AUTOSTART_DONE = "autostart_confirmed"
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"
    private const val TAG = "ReminderReliability"

    /** Every check that applies to this device, with its state. */
    fun statuses(context: Context): List<CheckStatus> = buildList {
        add(CheckStatus(ReliabilityCheck.NOTIFICATIONS, notificationsEnabled(context)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(CheckStatus(ReliabilityCheck.EXACT_ALARMS, canScheduleExact(context)))
        }
        add(CheckStatus(ReliabilityCheck.BATTERY, ignoresBatteryOptimizations(context)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add(CheckStatus(ReliabilityCheck.BACKGROUND, !isBackgroundRestricted(context)))
        }
        if (autostartIntent(context) != null) {
            add(CheckStatus(ReliabilityCheck.AUTOSTART, autostartConfirmed(context), verifiable = false))
        }
    }

    fun hasCriticalIssues(context: Context): Boolean = statuses(context).any { !it.ok }

    // ---- individual checks ----

    fun notificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notifications.ensureChannel(context)
            // Any reminder channel (one per sound) turned down in the system settings.
            if (Notifications.reminderChannels(context).any { it.importance < NotificationManager.IMPORTANCE_DEFAULT }) {
                return false
            }
        }
        return true
    }

    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        } else true

    fun ignoresBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isBackgroundRestricted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    // ---- user-confirmed state ----

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun autostartConfirmed(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOSTART_DONE, false)

    fun setAutostartConfirmed(context: Context, done: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTOSTART_DONE, done).apply()

    /** True only the first time it's called: used to show the checklist once after install. */
    fun consumeOnboarding(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(KEY_ONBOARDING_SHOWN, false)) return false
        p.edit().putBoolean(KEY_ONBOARDING_SHOWN, true).apply()
        return true
    }

    // ---- fixes ----

    /** The settings screen that fixes [check], or null if none applies. */
    // A medication reminder must fire on time, so asking for the exemption is the point.
    @SuppressLint("BatteryLife")
    fun fixIntent(context: Context, check: ReliabilityCheck): Intent? {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        return when (check) {
            ReliabilityCheck.NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                } else appDetails(pkgUri)
            ReliabilityCheck.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri)
                } else null
            // One-tap system dialog first; some OEMs drop it, then the list or app details.
            ReliabilityCheck.BATTERY ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
                    .takeIf { it.resolves(context) }
                    ?: Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .takeIf { it.resolves(context) }
                    ?: appDetails(pkgUri)
            ReliabilityCheck.BACKGROUND -> appDetails(pkgUri)
            ReliabilityCheck.AUTOSTART -> autostartIntent(context)
        }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Launch [intent], falling back to the app details screen if the OEM screen refuses. */
    fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${intent.component ?: intent.action}", e)
            runCatching {
                context.startActivity(
                    appDetails(Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private fun appDetails(pkgUri: Uri) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)

    private fun Intent.resolves(context: Context): Boolean =
        context.packageManager.resolveActivity(this, PackageManager.MATCH_DEFAULT_ONLY) != null

    /**
     * Known "auto-start / background launch" managers of OEM skins that kill apps
     * (MIUI, EMUI, ColorOS, Funtouch, One UI, OxygenOS, ZenUI...). The first one present
     * on this device wins. Package visibility is declared in the manifest `<queries>`.
     */
    private val autostartComponents = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
        "com.htc.pitroad" to "com.htc.pitroad.landingpage.activity.LandingPageActivity",
    )

    fun autostartIntent(context: Context): Intent? =
        autostartComponents.asSequence()
            .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
            .firstOrNull { intent ->
                val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                info?.activityInfo?.exported == true
            }

    /** Brand-specific hint for the auto-start item (falls back to a generic one). */
    @StringRes
    fun autostartTipRes(): Int = when (Build.MANUFACTURER.lowercase()) {
        "xiaomi", "redmi", "poco" -> R.string.rel_tip_xiaomi
        "huawei", "honor" -> R.string.rel_tip_huawei
        "oppo", "realme", "oneplus" -> R.string.rel_tip_oppo
        "vivo", "iqoo" -> R.string.rel_tip_vivo
        "samsung" -> R.string.rel_tip_samsung
        else -> R.string.rel_tip_generic
    }
}
