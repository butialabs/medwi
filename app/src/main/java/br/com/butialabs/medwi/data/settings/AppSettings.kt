package br.com.butialabs.medwi.data.settings

import android.content.Context

/** Small app-wide preferences readable from anywhere (receivers, widget, UI). */
object AppSettings {

    private const val PREFS = "app_settings"
    private const val KEY_LOCK = "lock_enabled"
    private const val KEY_SOUND = "reminder_sound"

    /** The phone's default notification sound (what reminders used before sounds were selectable). */
    const val DEFAULT_SOUND = "system:default"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** App lock on: unlock with the phone's biometrics/PIN; medicine names hidden outside the app. */
    fun isLockEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_LOCK, false)

    fun setLockEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_LOCK, enabled).apply()

    /** General reminder sound key (see ReminderSounds); medicines without their own use it. */
    fun reminderSound(context: Context): String = prefs(context).getString(KEY_SOUND, null) ?: DEFAULT_SOUND

    /** Whether the user ever chose a general sound (vs. still on the default). */
    fun hasReminderSound(context: Context): Boolean = prefs(context).contains(KEY_SOUND)

    fun setReminderSound(context: Context, key: String) =
        prefs(context).edit().putString(KEY_SOUND, key).apply()
}
