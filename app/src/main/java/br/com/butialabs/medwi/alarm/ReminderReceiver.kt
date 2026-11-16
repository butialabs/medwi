package br.com.butialabs.medwi.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.db.DoseItemEntity
import br.com.butialabs.medwi.data.db.effectiveAtMillis
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Three kinds of alarms land here:
 *  - the single "next dose" alarm: shows the reminder for every dose due at that time
 *    (several medications sharing a time get one combined notification), arms
 *    a "remind again" nag for each, and arms the following dose;
 *  - [ACTION_NAG]: re-alerts a dose that is still unanswered, up to [MAX_NAGS] times;
 *  - [ACTION_REFILL]: the daily "time to refill" reminder for low-stock medicines.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: MedRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val itemId = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
        if (action != ACTION_REFILL && itemId < 0) return
        val attempt = intent.getIntExtra(AlarmScheduler.EXTRA_ATTEMPT, 0)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val now = System.currentTimeMillis()
                when (action) {
                    ACTION_REFILL -> onRefill(context, now)
                    ACTION_NAG -> onNag(context, itemId, attempt, now)
                    else -> onDue(context, itemId, now)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun onDue(context: Context, itemId: Long, now: Long) {
        val fired = repository.doseById(itemId)
        val dueAt = fired?.effectiveAtMillis() ?: now
        val due = repository.awaitingAt(dueAt, now)
            .ifEmpty { listOfNotNull(fired).filter { it.isAwaitingAnswer(now) } }
        Log.d(TAG, "fired item=$itemId due=${due.map { it.id }}")
        if (due.isNotEmpty()) repository.showReminders(dueAt, due, silent = false)
        due.forEach { repository.scheduleNag(it.id, now + NAG_INTERVAL_MILLIS, 1) }
        // Arm the following dose: anything after this slot (a late alarm still chains
        // through doses that are already overdue instead of skipping them).
        repository.rescheduleNextAlarm(dueAt)
    }

    private suspend fun onNag(context: Context, itemId: Long, attempt: Int, now: Long) {
        val dose = repository.doseById(itemId) ?: return
        if (!dose.isAwaitingAnswer(now)) return
        if (attempt < MAX_NAGS) repository.scheduleNag(dose.id, now + NAG_INTERVAL_MILLIS, attempt + 1)
        // Every dose of a shared time slot carries its own nag; only the first one still
        // unanswered re-posts the slot, so the user is alerted once, not once per medicine.
        val dueAt = dose.effectiveAtMillis()
        val slot = repository.awaitingAt(dueAt, now).ifEmpty { listOf(dose) }
        if (slot.minOf { it.id } != dose.id) return
        repository.showReminders(dueAt, slot, silent = false)
    }

    private suspend fun onRefill(context: Context, now: Long) {
        repository.lowStockMedicines().forEach { Notifications.showRefill(context, it) }
        // Same time tomorrow (or whatever is next) while stock stays low.
        repository.rescheduleRefillReminder(now + 60_000L)
    }

    private fun DoseItemEntity.isAwaitingAnswer(now: Long): Boolean = when (status) {
        DoseStatus.PENDING.name -> true
        DoseStatus.SNOOZED.name -> (snoozedUntilMillis ?: 0L) <= now
        else -> false
    }

    companion object {
        const val ACTION_NAG = "br.com.butialabs.medwi.action.NAG"
        const val ACTION_REFILL = "br.com.butialabs.medwi.action.REFILL"
        /** "Remind again" cadence for unanswered doses. */
        const val NAG_INTERVAL_MILLIS = 10 * 60_000L
        const val MAX_NAGS = 3
        private const val TAG = "ReminderReceiver"
    }
}
