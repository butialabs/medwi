package br.com.butialabs.medwi.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Arms / cancels the single "next dose" alarm. Extracted as an interface so the
 * repository can be unit-tested with a fake that records calls instead of
 * touching the real AlarmManager.
 */
interface AlarmGateway {
    fun scheduleExact(itemId: Long, triggerAtMillis: Long)
    fun cancel()

    /** Arm a "remind again" alarm for one dose that hasn't been answered yet. */
    fun scheduleNag(itemId: Long, triggerAtMillis: Long, attempt: Int) {}

    /** Drop the pending "remind again" alarm of a dose (it was answered). */
    fun cancelNag(itemId: Long) {}

    /** Arm the single daily "time to refill" alarm. */
    fun scheduleRefill(triggerAtMillis: Long) {}

    fun cancelRefill() {}
}

/**
 * Wraps AlarmManager for exact dose reminders.
 *
 * On API 31+ it uses an exact alarm when permitted, otherwise it falls back to an
 * inexact wake alarm. Only the *next* dose
 * is armed at a time; the receiver re-arms the following one (keeps us within the
 * OS exact-alarm budget).
 */
class AlarmScheduler(private val context: Context) : AlarmGateway {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun scheduleExact(itemId: Long, triggerAtMillis: Long) {
        Log.d(TAG, "arm next dose item=$itemId at=$triggerAtMillis")
        setWakeAlarm(pendingIntent(itemId), triggerAtMillis)
    }

    override fun scheduleNag(itemId: Long, triggerAtMillis: Long, attempt: Int) {
        Log.d(TAG, "arm nag item=$itemId attempt=$attempt at=$triggerAtMillis")
        setWakeAlarm(nagIntent(itemId, attempt), triggerAtMillis)
    }

    override fun cancelNag(itemId: Long) {
        alarmManager.cancel(nagIntent(itemId, 0))
    }

    override fun scheduleRefill(triggerAtMillis: Long) {
        Log.d(TAG, "arm refill reminder at=$triggerAtMillis")
        setWakeAlarm(refillIntent(), triggerAtMillis)
    }

    override fun cancelRefill() {
        alarmManager.cancel(refillIntent())
    }

    private fun refillIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REFILL_REQUEST_CODE,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REFILL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun setWakeAlarm(pi: PendingIntent, triggerAtMillis: Long) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true

        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        } catch (se: SecurityException) {
            // Exact-alarm permission revoked at runtime -> best-effort inexact.
            Log.w(TAG, "Exact alarm denied, falling back", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    /**
     * One nag alarm per dose: same request code + action for a given item, so re-arming
     * replaces the previous one and [cancelNag] can find it regardless of [attempt].
     */
    private fun nagIntent(itemId: Long, attempt: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_NAG
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_ATTEMPT, attempt)
        }
        return PendingIntent.getBroadcast(
            context,
            (itemId % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun cancel() {
        alarmManager.cancel(pendingIntent(REQUEST_CODE.toLong()))
    }

    private fun pendingIntent(itemId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_ATTEMPT = "attempt"
        // Single reusable request code: we only ever keep one "next dose" alarm armed.
        private const val REQUEST_CODE = 1001
        private const val REFILL_REQUEST_CODE = 1002
        private const val TAG = "AlarmScheduler"
    }
}
