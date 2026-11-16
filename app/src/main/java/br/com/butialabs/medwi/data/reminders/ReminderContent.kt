package br.com.butialabs.medwi.data.reminders

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

@Serializable
data class ReminderMessage(
    val title: String = "",
    val subtitle: String = "",
    val body: String = "",
)

/**
 * Localized reminder notification copy, loaded from assets/reminder_messages.json
 * (en, pt, es). Messages rotate with each snooze; "{{reminder.actualTime}}" is replaced
 * with the dose time. Unknown languages fall back to English.
 */
class ReminderContent(private val appContext: Context) {

    @Volatile
    private var byLocale: Map<String, List<ReminderMessage>> = emptyMap()

    private fun ensureLoaded() {
        if (byLocale.isNotEmpty()) return
        synchronized(this) {
            if (byLocale.isEmpty()) {
                val text = appContext.assets.open("reminder_messages.json")
                    .bufferedReader().use { it.readText() }
                byLocale = json.decodeFromString<Map<String, List<ReminderMessage>>>(text)
            }
        }
    }

    /** A reminder message for the current locale, rotating by [index], with time filled in. */
    fun message(timeLabel: String, index: Int = 0): ReminderMessage {
        ensureLoaded()
        val lang = Locale.getDefault().language.lowercase()
        val list = byLocale[lang] ?: byLocale["en"] ?: return fallback(timeLabel)
        if (list.isEmpty()) return fallback(timeLabel)
        val m = list[index % list.size]
        return ReminderMessage(
            title = m.title.fill(timeLabel),
            subtitle = m.subtitle.fill(timeLabel),
            body = m.body.fill(timeLabel),
        )
    }

    private fun fallback(timeLabel: String) =
        ReminderMessage("Time for your medication", "", "Take your $timeLabel dose.")

    private fun String.fill(timeLabel: String) =
        replace("{{reminder.actualTime}}", timeLabel)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
