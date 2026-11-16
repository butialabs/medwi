package br.com.butialabs.medwi.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.db.DoseItemEntity
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.reminders.ReminderContent
import br.com.butialabs.medwi.data.settings.AppSettings
import br.com.butialabs.medwi.ui.MainActivity
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import br.com.butialabs.medwi.ui.doseText
import br.com.butialabs.medwi.ui.medTitle
import br.com.butialabs.medwi.ui.pill.PillRenderer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds and posts the dose reminder notification with Take / Snooze / Skip actions.
 *
 * The reminder is "sticky": it is ongoing, does not auto-cancel, and if the user still
 * manages to swipe it away (allowed for ongoing notifications on Android 14+) the delete
 * intent re-posts it while the dose is unanswered. It only goes away through an answer.
 *
 * Several doses due at the same time share a single notification ("3 medicines at
 * 08:00") with Take all / Snooze / Choose. With the app lock on, the lock-screen
 * version hides medicine names.
 */
object Notifications {

    /**
     * A channel's sound can't be changed after creation, so every reminder sound gets its
     * own channel ("Reminders · Chime"), created on demand. The id hashes the sound key and
     * the URI it plays, so a re-published custom file gets a fresh channel.
     */
    private const val CHANNEL_PREFIX = "med_reminder_"
    private const val GROUP_ID = "med_reminders"
    /** Single-channel era (before selectable sounds); migrated once, then deleted. */
    private const val V3_CHANNEL_ID = "med_reminders_v3"
    private val LEGACY_CHANNEL_IDS = listOf("med_reminders", "med_reminders_v2", V3_CHANNEL_ID)
    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private const val TEST_NOTIF_ID = 899_999
    private const val REFILL_NOTIF_BASE = 900_000
    private const val SLOT_NOTIF_BASE = 700_000

    private fun nm(context: Context) = context.getSystemService(NotificationManager::class.java)

    /** Migrate the old single channel and make sure the general sound's channel exists. */
    fun ensureChannel(context: Context): String {
        migrateLegacyChannel(context)
        return channelFor(context, AppSettings.reminderSound(context))
    }

    /**
     * A sound the user set on the old channel in the system settings becomes the general
     * sound, so updating doesn't silently change what they hear.
     */
    private fun migrateLegacyChannel(context: Context) {
        val nm = nm(context)
        val old = nm.getNotificationChannel(V3_CHANNEL_ID)
        if (old != null && !AppSettings.hasReminderSound(context)) {
            val sound = old.sound
            val key = when (sound) {
                null -> ReminderSounds.SILENT
                Settings.System.DEFAULT_NOTIFICATION_URI -> null
                else -> ReminderSounds.systemKey(sound)
            }
            if (key != null) AppSettings.setReminderSound(context, key)
        }
        LEGACY_CHANNEL_IDS.forEach { id ->
            if (nm.getNotificationChannel(id) != null) nm.deleteNotificationChannel(id)
        }
    }

    /** Channel playing [soundKey] (falls back when an uploaded file is gone), created if needed. */
    fun channelFor(context: Context, soundKey: String): String {
        val key = ReminderSounds.effective(context, soundKey)
        val uri = ReminderSounds.uriFor(context, key)
        val id = channelId(key, uri)
        val nm = nm(context)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannelGroup(
                NotificationChannelGroup(GROUP_ID, context.getString(R.string.notif_channel_group))
            )
            val name = context.getString(
                R.string.notif_channel_reminders_sound,
                context.getString(R.string.notif_channel_reminders),
                ReminderSounds.displayName(context, key),
            )
            val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.notif_channel_reminders_desc)
                group = GROUP_ID
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 250, 400)
                enableLights(true)
                lightColor = ContextCompat.getColor(context, R.color.brand_green)
                setShowBadge(true)
                setSound(
                    uri,
                    if (uri == null) null else AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
            nm.createNotificationChannel(channel)
        }
        return id
    }

    private fun channelId(key: String, uri: Uri?): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$key|$uri".toByteArray())
        return CHANNEL_PREFIX + digest.take(6).joinToString("") { "%02x".format(it) }
    }

    /** All reminder channels Medwi created (for the reliability check). */
    fun reminderChannels(context: Context): List<NotificationChannel> =
        nm(context).notificationChannels.filter { it.id.startsWith(CHANNEL_PREFIX) }

    /**
     * Delete reminder channels no longer used by any sound in [usedKeys] (the general sound
     * is always kept). A channel with a reminder on screen is kept too: deleting it would
     * remove that reminder.
     */
    fun pruneChannels(context: Context, usedKeys: Collection<String>) {
        val nm = nm(context)
        val keep = (usedKeys + AppSettings.reminderSound(context)).mapTo(HashSet()) { channelFor(context, it) }
        val showing = nm.activeNotifications.mapNotNullTo(HashSet()) { it.notification.channelId }
        reminderChannels(context)
            .filter { it.id !in keep && it.id !in showing }
            .forEach { nm.deleteNotificationChannel(it.id) }
    }

    /** Notification id derived from the dose id so each dose has its own notification. */
    fun notificationId(itemId: Long): Int = (itemId % Int.MAX_VALUE).toInt()

    /** Id of the combined "N medicines at 08:00" notification for a time slot. */
    fun slotNotificationId(dueAtMillis: Long): Int = SLOT_NOTIF_BASE + ((dueAtMillis / 60_000L) % 100_000L).toInt()

    fun cancel(context: Context, itemId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(itemId))
    }

    /** "Euthyrox 125 mg · 1 comprimido" for a dose. */
    fun doseLabel(context: Context, view: DoseView): String =
        medTitle(view.medicineName, view.strengthValue, view.strengthUnit) +
            " · " + doseText(context.resources, view.dosageValue, view.dosageUnit)

    /** The medicine's own drawing (shape + colors) as the notification's large icon. */
    /** Null if drawing fails: the reminder must go out even without its picture. */
    private fun pillIcon(context: Context, view: DoseView): Bitmap? = runCatching {
        val px = (64 * context.resources.displayMetrics.density).toInt()
        PillRenderer.bitmap(view.iconKey, view.colorHex, view.color2Hex, px, Color(0xFFEAF0E5))
    }.getOrNull()

    private fun timeLabel(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)

    /**
     * Post (or re-post) the reminder for a single [dose]. [label] is the medicine +
     * quantity line. [silent] re-posts without sound/vibration (restoring a swiped-away
     * reminder); otherwise it alerts every time, so a "remind again" nag is noticed.
     */
    fun showReminder(
        context: Context,
        dose: DoseItemEntity,
        content: ReminderContent,
        label: String? = null,
        silent: Boolean = false,
        view: DoseView? = null,
        soundKey: String = AppSettings.reminderSound(context),
    ) {
        val channel = channelFor(context, soundKey)

        val time = timeLabel(dose.scheduledAtMillis)
        val msg = content.message(time, index = dose.snoozeCounter)
        val text = label ?: msg.body
        val big = if (label != null) "$label\n${msg.body}" else msg.body

        val notifId = notificationId(dose.id)
        val builder = baseReminder(context, channel, dose.scheduledAtMillis, silent)
            .setContentTitle(msg.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPublicVersion(publicVersion(context, channel, time))
            .apply { view?.let { pillIcon(context, it) }?.let(::setLargeIcon) }
            .setDeleteIntent(
                actionIntent(context, ReminderActionReceiver.ACTION_DISMISSED, dose, notifId)
            )
            .addAction(0, context.getString(R.string.action_take),
                actionIntent(context, ReminderActionReceiver.ACTION_TAKE, dose, notifId))
            .addAction(0, context.getString(R.string.action_snooze),
                actionIntent(context, ReminderActionReceiver.ACTION_SNOOZE, dose, notifId))
            .addAction(0, context.getString(R.string.action_skip),
                actionIntent(context, ReminderActionReceiver.ACTION_SKIP, dose, notifId))
        post(context, notifId, builder)
    }

    /**
     * One notification for a time slot with several doses: "3 medicines at 08:00", one
     * line per medicine, and Take all / Snooze / Choose (opens the app to answer one by one).
     * [labels] are the per-dose lines.
     */
    fun showSlot(
        context: Context,
        dueAtMillis: Long,
        doses: List<DoseItemEntity>,
        labels: List<String>,
        silent: Boolean = false,
        soundKey: String = AppSettings.reminderSound(context),
    ) {
        val channel = channelFor(context, soundKey)
        val plannedAt = doses.minOf { it.scheduledAtMillis }
        val time = timeLabel(plannedAt)
        val title = context.getString(R.string.slot_title, doses.size, time)
        val notifId = slotNotificationId(dueAtMillis)
        val ids = doses.map { it.id }
        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        labels.forEach { inbox.addLine(it) }

        // Doses of this slot may still have single notifications from an earlier post.
        ids.forEach { cancel(context, it) }
        val builder = baseReminder(context, channel, plannedAt, silent)
            .setContentTitle(title)
            .setContentText(labels.joinToString(", "))
            .setStyle(inbox)
            .setNumber(doses.size)
            .setPublicVersion(publicVersion(context, channel, time))
            .setDeleteIntent(slotIntent(context, ReminderActionReceiver.ACTION_SLOT_DISMISSED, ids, dueAtMillis, notifId))
            .addAction(0, context.getString(R.string.take_all_count, doses.size),
                slotIntent(context, ReminderActionReceiver.ACTION_TAKE_ALL, ids, dueAtMillis, notifId))
            .addAction(0, context.getString(R.string.action_snooze),
                slotIntent(context, ReminderActionReceiver.ACTION_SNOOZE_ALL, ids, dueAtMillis, notifId))
            .addAction(0, context.getString(R.string.action_choose), openAppIntent(context))
        post(context, notifId, builder)
    }

    fun cancelSlot(context: Context, dueAtMillis: Long) {
        NotificationManagerCompat.from(context).cancel(slotNotificationId(dueAtMillis))
    }

    /** Whether the combined notification of this slot is currently on screen. */
    fun isSlotShown(context: Context, dueAtMillis: Long): Boolean {
        val id = slotNotificationId(dueAtMillis)
        return context.getSystemService(NotificationManager::class.java)
            ?.activeNotifications?.any { it.id == id } == true
    }

    /**
     * Slot-wide action (Take all / Snooze all / dismissed). The action makes each intent
     * distinct; [requestCode] keeps one slot's intents apart from another's.
     */
    private fun slotIntent(context: Context, action: String, ids: List<Long>, dueAtMillis: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderActionReceiver.EXTRA_ITEM_IDS, ids.toLongArray())
            putExtra(ReminderActionReceiver.EXTRA_DUE_AT, dueAtMillis)
            putExtra(ReminderActionReceiver.EXTRA_NOTIF_ID, requestCode)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun baseReminder(context: Context, channel: String, whenMillis: Long, silent: Boolean) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_medwi)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(
                if (AppSettings.isLockEnabled(context)) NotificationCompat.VISIBILITY_PRIVATE
                else NotificationCompat.VISIBILITY_PUBLIC
            )
            .setWhen(whenMillis)
            .setShowWhen(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(silent)
            .setContentIntent(openAppIntent(context))

    /** What the lock screen shows when the app lock hides details. */
    private fun publicVersion(context: Context, channel: String, time: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_medwi)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setContentTitle(context.getString(R.string.notif_private_title))
            .setContentText(context.getString(R.string.notif_private_body, time))
            .build()

    /** Low-stock reminder; offers "+1 box" when the box size is known. */
    fun showRefill(context: Context, medicine: MedicineEntity) {
        val channel = ensureChannel(context)
        val hidden = AppSettings.isLockEnabled(context)
        val name = if (hidden) context.getString(R.string.notif_private_med) else medicine.name
        val notifId = refillNotificationId(medicine.id)
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_medwi)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setContentTitle(context.getString(R.string.refill_notif_title, name))
            .setContentText(context.getString(R.string.refill_notif_body, medicine.stockCount ?: 0))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
        val box = medicine.unitsPerPackage
        if (box != null && box > 0) {
            val add = Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_ADD_PACKAGE
                putExtra(ReminderActionReceiver.EXTRA_MEDICINE_ID, medicine.id)
                putExtra(ReminderActionReceiver.EXTRA_AMOUNT, box)
                putExtra(ReminderActionReceiver.EXTRA_NOTIF_ID, notifId)
            }
            builder.addAction(
                0, context.getString(R.string.refill_add_box_action, box),
                PendingIntent.getBroadcast(
                    context, notifId, add, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        post(context, notifId, builder)
    }

    fun refillNotificationId(medicineId: Long): Int = REFILL_NOTIF_BASE + (medicineId % 90_000L).toInt()

    fun cancelRefill(context: Context, medicineId: Long) {
        NotificationManagerCompat.from(context).cancel(refillNotificationId(medicineId))
    }

    /**
     * Fires a sample reminder right away so the user can check sound/heads-up/lock screen.
     * Plays [soundKey] (the general sound by default).
     */
    fun showTest(context: Context, soundKey: String = AppSettings.reminderSound(context)) {
        migrateLegacyChannel(context)
        val builder = NotificationCompat.Builder(context, channelFor(context, soundKey))
            .setSmallIcon(R.drawable.ic_stat_medwi)
            .setColor(ContextCompat.getColor(context, R.color.brand_green))
            .setContentTitle(context.getString(R.string.test_notif_title))
            .setContentText(context.getString(R.string.test_notif_body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
        post(context, TEST_NOTIF_ID, builder)
    }

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the reliability checklist flags it.
        }
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(
        context: Context, action: String, dose: DoseItemEntity, notifId: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmScheduler.EXTRA_ITEM_ID, dose.id)
            // Stable identity, in case the id is stale by the time the user answers.
            putExtra(ReminderActionReceiver.EXTRA_GROUP_ID, dose.groupId)
            putExtra(ReminderActionReceiver.EXTRA_SCHEDULED_AT, dose.scheduledAtMillis)
            putExtra(ReminderActionReceiver.EXTRA_NOTIF_ID, notifId)
        }
        // The request code only needs to differ per dose: the action already makes each
        // (action, dose) PendingIntent distinct.
        return PendingIntent.getBroadcast(
            context, notifId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
