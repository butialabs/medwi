package br.com.butialabs.medwi.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.db.effectiveAtMillis
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles notification actions: Take / Snooze / Skip, Take all / Snooze all for a time slot,
 * "+1 box" on the refill reminder, and the delete intent fired when a sticky reminder
 * is swiped away.
 */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: MedRepository

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_TAKE_ALL -> {
                        val ids = intent.getLongArrayExtra(EXTRA_ITEM_IDS)?.toList().orEmpty()
                        val done = repository.markDoses(ids, DoseStatus.TAKEN)
                        Log.d(TAG, "take all: $done/${ids.size}")
                        cancel(context, notifId)
                    }
                    ACTION_SNOOZE_ALL -> {
                        val ids = intent.getLongArrayExtra(EXTRA_ITEM_IDS)?.toList().orEmpty()
                        repository.snoozeAll(ids, minutes = SNOOZE_MINUTES)
                        cancel(context, notifId)
                    }
                    ACTION_SLOT_DISMISSED -> {
                        // Swiped away while doses are still open -> put it back, quietly.
                        val dueAt = intent.getLongExtra(EXTRA_DUE_AT, -1L)
                        if (dueAt >= 0) {
                            val open = repository.awaitingAt(dueAt)
                            if (open.isNotEmpty()) repository.showReminders(dueAt, open, silent = true)
                        }
                    }
                    ACTION_ADD_PACKAGE -> {
                        val medId = intent.getLongExtra(EXTRA_MEDICINE_ID, -1L)
                        val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
                        if (medId >= 0 && amount > 0) repository.addStock(medId, amount)
                        cancel(context, notifId)
                    }
                    else -> onDoseAction(context, action, intent, notifId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun onDoseAction(context: Context, action: String, intent: Intent, notifId: Int) {
        val itemId = intent.getLongExtra(AlarmScheduler.EXTRA_ITEM_ID, -1L)
        val groupId = intent.getLongExtra(EXTRA_GROUP_ID, -1L)
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, -1L)
        if (itemId < 0) return

        val dose = repository.resolveDose(itemId, groupId, scheduledAt)
        if (dose == null) {
            Log.w(TAG, "$action: dose not found (id=$itemId group=$groupId at=$scheduledAt)")
            cancel(context, notifId)
            return
        }
        if (dose.id != itemId) Log.i(TAG, "$action: stale id $itemId resolved to ${dose.id}")

        when (action) {
            ACTION_TAKE -> repository.markDose(dose.id, DoseStatus.TAKEN)
            ACTION_SKIP -> repository.markDose(dose.id, DoseStatus.SKIPPED)
            ACTION_SNOOZE -> repository.snooze(dose.id, minutes = SNOOZE_MINUTES)
            ACTION_DISMISSED -> {
                // Swiped away while still unanswered -> put it back, quietly.
                val unanswered = dose.status == DoseStatus.PENDING.name ||
                    (dose.status == DoseStatus.SNOOZED.name &&
                        (dose.snoozedUntilMillis ?: 0L) <= System.currentTimeMillis())
                if (unanswered) repository.showReminders(dose.effectiveAtMillis(), listOf(dose), silent = true)
                return
            }
        }
        // markDose/snooze cancel the notification of the resolved id; also clear the
        // one that was tapped in case it was posted under a stale id.
        cancel(context, notifId)
    }

    private fun cancel(context: Context, notifId: Int) {
        if (notifId >= 0) NotificationManagerCompat.from(context).cancel(notifId)
    }

    companion object {
        const val ACTION_TAKE = "br.com.butialabs.medwi.action.TAKE"
        const val ACTION_SNOOZE = "br.com.butialabs.medwi.action.SNOOZE"
        const val ACTION_SKIP = "br.com.butialabs.medwi.action.SKIP"
        const val ACTION_DISMISSED = "br.com.butialabs.medwi.action.DISMISSED"
        const val ACTION_TAKE_ALL = "br.com.butialabs.medwi.action.TAKE_ALL"
        const val ACTION_SNOOZE_ALL = "br.com.butialabs.medwi.action.SNOOZE_ALL"
        const val ACTION_SLOT_DISMISSED = "br.com.butialabs.medwi.action.SLOT_DISMISSED"
        const val ACTION_ADD_PACKAGE = "br.com.butialabs.medwi.action.ADD_PACKAGE"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        const val EXTRA_ITEM_IDS = "item_ids"
        const val EXTRA_DUE_AT = "due_at"
        const val EXTRA_MEDICINE_ID = "medicine_id"
        const val EXTRA_AMOUNT = "amount"
        const val SNOOZE_MINUTES = 15
        private const val TAG = "ReminderActionReceiver"
    }
}
