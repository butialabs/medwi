package br.com.butialabs.medwi.data

import android.content.Context
import br.com.butialabs.medwi.alarm.AlarmGateway
import br.com.butialabs.medwi.alarm.Notifications
import br.com.butialabs.medwi.alarm.ReminderSounds
import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.scheduler.Scheduler
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.db.AsNeededMed
import br.com.butialabs.medwi.data.db.DoseItemEntity
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.data.db.MedListItem
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.RefillEventEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import br.com.butialabs.medwi.data.db.effectiveAtMillis
import br.com.butialabs.medwi.data.reminders.ReminderContent
import br.com.butialabs.medwi.data.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Told whenever data shown outside the app (home-screen widget) may have changed. */
fun interface DataChangeListener {
    fun onDataChanged()

    companion object {
        val NONE = DataChangeListener { }
    }
}

/** Optional schedule rules collected by the wizard, persisted next to the schedule. */
data class ScheduleExtras(
    val doseIntervalMinutes: Int? = null,
    val doseStep: Float? = null,
    val doseStepEveryDays: Int? = null,
    val doseFinal: Float? = null,
)

/**
 * Single source of truth: converts between entities and the pure-Kotlin
 * [Scheduler], persists dose items, and keeps the next alarm armed.
 *
 * Flow: add/edit med -> generate items -> reschedule alarm.
 */
class MedRepository(
    private val appContext: Context,
    private val db: AppDatabase,
    private val scheduler: Scheduler,
    private val alarms: AlarmGateway,
    private val changes: DataChangeListener = DataChangeListener.NONE,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val reminderContent: ReminderContent = ReminderContent(appContext),
) {
    private val medicineDao = db.medicineDao()
    private val groupDao = db.scheduleGroupDao()
    private val doseDao = db.doseItemDao()
    private val refillDao = db.refillEventDao()

    // ------------------------------------------------------------------ observe

    fun observeDoses(from: Long, to: Long): Flow<List<DoseView>> =
        doseDao.observeViewsBetween(from, to)

    suspend fun dosesBetween(from: Long, to: Long): List<DoseView> = doseDao.viewsBetween(from, to)

    /** "As needed" medications, shown on Today with a one-tap "log dose" action. */
    fun observeAsNeeded(): Flow<List<AsNeededMed>> = groupDao.observeAsNeeded()

    fun observeMedList(): Flow<List<MedListItem>> = medicineDao.observeListItems()

    fun observeMedicine(medicineId: Long): Flow<MedicineEntity?> = medicineDao.observeById(medicineId)

    fun observeGroupFor(medicineId: Long): Flow<ScheduleGroupEntity?> = groupDao.observeForMedicine(medicineId)

    fun observeRefills(medicineId: Long): Flow<List<RefillEventEntity>> = refillDao.observeForMedicine(medicineId)

    // ------------------------------------------------------------------ medications

    /** Record an extra, unscheduled dose taken now (any medication, scheduled or as-needed). */
    suspend fun logExtraDose(groupId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val group = groupDao.byId(groupId) ?: return
        val dose = DoseItemEntity(
            groupId = groupId,
            scheduledAtMillis = nowMillis,
            actualAtMillis = nowMillis,
            dosageValue = group.dosageValue,
            dosageUnit = group.dosageUnit,
            status = DoseStatus.TAKEN.name,
        )
        doseDao.upsertAll(listOf(dose))
        adjustStock(dose, -1)
        changed()
    }

    /** Create a medication + its schedule, materialize items, arm the alarm. */
    suspend fun addMedication(
        medicine: MedicineEntity,
        dosageValue: Float,
        dosageUnit: String,
        schedule: Schedule,
        nowMillis: Long = System.currentTimeMillis(),
        extras: ScheduleExtras = ScheduleExtras(),
    ): Long {
        val medId = medicineDao.insert(medicine)
        val groupEntity = ScheduleGroupEntity(
            medicineId = medId,
            dosageValue = dosageValue,
            dosageUnit = dosageUnit,
            scheduleType = schedule.type.name,
            consumptionTimes = schedule.consumptionTimes.joinToString(",") { it.toString() },
            startDateEpochDay = schedule.startDateEpochDay,
            endDateEpochDay = schedule.endDateEpochDay,
            everyXDays = schedule.everyXDays,
            daysOfWeek = schedule.daysOfWeek.sorted().joinToString(","),
            cyclePillDays = schedule.cyclePillDays,
            cycleBreakDays = schedule.cycleBreakDays,
            everyXMonths = schedule.everyXMonths,
        ).withExtras(extras)
        val groupId = groupDao.insert(groupEntity)
        regenerate(groupId, nowMillis)
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        if (medicine.reminderSound != null) pruneSoundChannels()
        changed()
        return groupId
    }

    /** Load a medicine together with its (single) schedule group, for editing. */
    suspend fun medicineWithGroup(medicineId: Long): Pair<MedicineEntity, ScheduleGroupEntity>? {
        val med = medicineDao.byId(medicineId) ?: return null
        val group = groupDao.forMedicine(medicineId) ?: return null
        return med to group
    }

    /** Update an existing medication + its schedule, re-materialize doses, re-arm. */
    suspend fun updateMedication(
        medicineId: Long,
        groupId: Long,
        medicine: MedicineEntity,
        dosageValue: Float,
        dosageUnit: String,
        schedule: Schedule,
        nowMillis: Long = System.currentTimeMillis(),
        extras: ScheduleExtras = ScheduleExtras(),
    ) {
        medicineDao.update(medicine.copy(id = medicineId))
        val existing = groupDao.byId(groupId) ?: return
        groupDao.update(
            existing.copy(
                dosageValue = dosageValue,
                dosageUnit = dosageUnit,
                scheduleType = schedule.type.name,
                consumptionTimes = schedule.consumptionTimes.joinToString(",") { it.toString() },
                startDateEpochDay = schedule.startDateEpochDay,
                endDateEpochDay = schedule.endDateEpochDay,
                everyXDays = schedule.everyXDays,
                daysOfWeek = schedule.daysOfWeek.sorted().joinToString(","),
                cyclePillDays = schedule.cyclePillDays,
                cycleBreakDays = schedule.cycleBreakDays,
                everyXMonths = schedule.everyXMonths,
            ).withExtras(extras)
        )
        if (existing.active) regenerate(groupId, nowMillis)
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        pruneSoundChannels()
        changed()
    }

    private fun ScheduleGroupEntity.withExtras(e: ScheduleExtras) = copy(
        doseIntervalMinutes = e.doseIntervalMinutes,
        doseStep = e.doseStep,
        doseStepEveryDays = e.doseStepEveryDays,
        doseFinal = e.doseFinal,
    )

    /** Delete a medication and everything under it (schedule groups + doses cascade), then re-arm. */
    suspend fun deleteMedicine(medicineId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val med = medicineDao.byId(medicineId) ?: return
        groupDao.forMedicine(medicineId)?.let { dropFutureDoses(it.id, nowMillis) }
        medicineDao.delete(med)
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        if (med.reminderSound != null) pruneSoundChannels()
        changed()
    }

    // ------------------------------------------------------------------ pause / resume / end

    /** Stop generating doses (keeps history); future un-acted doses are dropped. */
    suspend fun pause(groupId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val group = groupDao.byId(groupId) ?: return
        groupDao.update(group.copy(active = false, pausedAtMillis = nowMillis))
        dropFutureDoses(groupId, nowMillis)
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        changed()
    }

    /** Resume a paused (or re-open an ended) treatment from now on. */
    suspend fun resume(groupId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val group = groupDao.byId(groupId) ?: return
        val reopened = group.endedAtMillis != null
        groupDao.update(
            group.copy(
                active = true,
                pausedAtMillis = null,
                endedAtMillis = null,
                // An ended treatment had its end date set to the day it ended.
                endDateEpochDay = if (reopened) null else group.endDateEpochDay,
            )
        )
        regenerate(groupId, nowMillis)
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        changed()
    }

    /** End the treatment today: no more doses, history kept, listed under "Ended". */
    suspend fun end(groupId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val group = groupDao.byId(groupId) ?: return
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().toEpochDay()
        groupDao.update(
            group.copy(
                active = false,
                pausedAtMillis = null,
                endedAtMillis = nowMillis,
                endDateEpochDay = today,
            )
        )
        dropFutureDoses(groupId, nowMillis)
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        changed()
    }

    private suspend fun dropFutureDoses(groupId: Long, nowMillis: Long) {
        val future = doseDao.futureUnanswered(groupId, nowMillis)
        if (future.isEmpty()) return
        future.forEach { dismissReminder(it.id) }
        doseDao.deleteByIds(future.map { it.id })
    }

    // ------------------------------------------------------------------ generation / alarms

    /**
     * Re-materialize the window for one group, preserving acted-on doses AND the row
     * ids of doses that still exist. Ids must stay stable: a posted notification and
     * the armed alarm reference a dose by id, so churning them (delete + re-insert)
     * made "Take" from the notification silently miss its dose.
     *
     * Doses that would fall before [nowMillis] and never existed are not created: a
     * medication added at 10:00 doesn't get a "missed" 08:00 dose.
     */
    suspend fun regenerate(groupId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val groupEntity = groupDao.byId(groupId) ?: return
        val group = groupEntity.toCore()
        val existing = doseDao.forGroup(groupId)
        val existingByKey = existing.associateBy { it.scheduledAtMillis }
        val reconciled = scheduler.createOrEditItems(group, existing.map { it.toCore() }, nowMillis)
        val keep = reconciled.mapTo(HashSet()) { it.key }

        // Future pending doses the schedule no longer produces. Past ones stay as history.
        val stale = existing.filter {
            it.status == DoseStatus.PENDING.name &&
                it.scheduledAtMillis > nowMillis &&
                it.scheduledAtMillis !in keep
        }
        if (stale.isNotEmpty()) {
            stale.forEach { dismissReminder(it.id) }
            doseDao.deleteByIds(stale.map { it.id })
        }

        val writes = reconciled.mapNotNull { item ->
            val prior = existingByKey[item.key]
            when {
                prior == null -> if (item.scheduledAtMillis < nowMillis) null else item.toEntity()
                prior.status != DoseStatus.PENDING.name -> null // acted on: leave untouched
                prior.dosageValue == item.dosageValue && prior.dosageUnit == item.dosageUnit -> null
                else -> prior.copy(dosageValue = item.dosageValue, dosageUnit = item.dosageUnit)
            }
        }
        if (writes.isNotEmpty()) doseDao.upsertAll(writes)
    }

    /** Recompute all active groups (used on boot / timezone change / restore). */
    suspend fun regenerateAll(nowMillis: Long = System.currentTimeMillis()) {
        groupDao.allActive().forEach { regenerate(it.id, nowMillis) }
        rescheduleNextAlarm(nowMillis)
        rescheduleRefillReminder(nowMillis)
        changed()
    }

    /** Arm an exact alarm for the next un-acted dose after [nowMillis] (one alarm at a time). */
    suspend fun rescheduleNextAlarm(nowMillis: Long = System.currentTimeMillis()) {
        val next = doseDao.nextPending(nowMillis)
        if (next != null) {
            alarms.scheduleExact(next.id, next.effectiveAtMillis())
        } else {
            alarms.cancel()
        }
    }

    suspend fun doseById(itemId: Long): DoseItemEntity? = doseDao.byId(itemId)

    /** A dose joined with its medicine (name, strength) for notification text. */
    suspend fun doseView(itemId: Long): DoseView? = doseDao.viewById(itemId)

    /** Doses whose reminder is due at exactly [atMillis] (all meds sharing that time). */
    suspend fun dosesDueAt(atMillis: Long): List<DoseItemEntity> = doseDao.dueAt(atMillis)

    /**
     * Find a dose by id, falling back to its stable identity (group + planned time)
     * in case the id went stale between posting a notification and acting on it.
     */
    suspend fun resolveDose(itemId: Long, groupId: Long = -1, scheduledAtMillis: Long = -1): DoseItemEntity? =
        doseDao.byId(itemId)
            ?: if (groupId >= 0 && scheduledAtMillis >= 0) doseDao.byKey(groupId, scheduledAtMillis) else null

    /**
     * Show the reminders of one time slot: a single dose gets its own notification,
     * several share one combined notification. An empty list clears the slot.
     */
    suspend fun showReminders(dueAtMillis: Long, doses: List<DoseItemEntity>, silent: Boolean) {
        val global = AppSettings.reminderSound(appContext)
        when (doses.size) {
            0 -> Notifications.cancelSlot(appContext, dueAtMillis)
            1 -> {
                Notifications.cancelSlot(appContext, dueAtMillis)
                val view = doseDao.viewById(doses[0].id)
                val label = view?.let { Notifications.doseLabel(appContext, it) }
                val sound = ReminderSounds.slotSound(listOf(view?.reminderSound), global)
                Notifications.showReminder(appContext, doses[0], reminderContent, label, silent, view, sound)
            }
            else -> {
                val views = doses.map { doseDao.viewById(it.id) }
                val labels = doses.mapIndexed { i, d ->
                    views[i]?.let { Notifications.doseLabel(appContext, it) } ?: "#${d.id}"
                }
                val sound = ReminderSounds.slotSound(views.map { it?.reminderSound }, global)
                Notifications.showSlot(appContext, dueAtMillis, doses, labels, silent, sound)
            }
        }
    }

    // ------------------------------------------------------------------ reminder sounds

    /** Set a medicine's own reminder sound; null = use the general sound. */
    suspend fun setMedicineSound(medicineId: Long, soundKey: String?) {
        medicineDao.setReminderSound(medicineId, soundKey)
        pruneSoundChannels()
    }

    /** Set the general reminder sound (medicines without their own use it). */
    suspend fun setGeneralSound(soundKey: String) {
        AppSettings.setReminderSound(appContext, soundKey)
        pruneSoundChannels()
    }

    /** Delete an uploaded sound; medicines using it go back to the general sound. */
    suspend fun deleteCustomSound(soundKey: String) {
        medicineDao.clearReminderSound(soundKey)
        ReminderSounds.deleteCustom(appContext, soundKey)
        pruneSoundChannels()
    }

    /** Drop notification channels of sounds nobody uses anymore. */
    suspend fun pruneSoundChannels() {
        val used = medicineDao.reminderSoundsInUse()
        runCatching { Notifications.pruneChannels(appContext, used) }
    }

    /** Doses at [dueAtMillis] still waiting for an answer (pending, or snoozed and due). */
    suspend fun awaitingAt(dueAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): List<DoseItemEntity> =
        doseDao.dueAt(dueAtMillis).filter { it.isAwaitingAnswer(nowMillis) }

    /**
     * After answering doses of a slot whose combined notification is on screen, show what
     * is left (quietly), or clear it.
     */
    private suspend fun refreshShownSlots(dueAts: Collection<Long>) {
        dueAts.distinct().filter { Notifications.isSlotShown(appContext, it) }.forEach { at ->
            showReminders(at, awaitingAt(at), silent = true)
        }
    }

    /** Arm the "remind again" alarm for an unanswered dose. */
    fun scheduleNag(itemId: Long, atMillis: Long, attempt: Int) =
        alarms.scheduleNag(itemId, atMillis, attempt)

    // ------------------------------------------------------------------ answering doses

    /**
     * Apply a user action to a dose and re-arm the next alarm. Also used to correct
     * history: [DoseStatus.PENDING] undoes a previous answer. For TAKEN, the time
     * recorded is [takenAtMillis] if given ("I took it at 07:40"), else the planned time
     * when [retroactive], else now. Returns false when the dose doesn't exist.
     */
    suspend fun markDose(
        itemId: Long,
        status: DoseStatus,
        nowMillis: Long = System.currentTimeMillis(),
        retroactive: Boolean = false,
        takenAtMillis: Long? = null,
    ): Boolean {
        val dueAt = doseDao.byId(itemId)?.effectiveAtMillis()
        val ok = applyStatus(itemId, status, nowMillis, retroactive, takenAtMillis)
        if (ok) {
            refreshShownSlots(listOfNotNull(dueAt))
            rescheduleNextAlarm(nowMillis)
            changed()
        }
        return ok
    }

    /** "Take all": answer several doses at once, re-arming the alarm a single time. */
    suspend fun markDoses(
        itemIds: Collection<Long>,
        status: DoseStatus,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        val dueAts = itemIds.mapNotNull { doseDao.byId(it)?.effectiveAtMillis() }
        val done = itemIds.count { applyStatus(it, status, nowMillis, retroactive = false, takenAtMillis = null) }
        refreshShownSlots(dueAts)
        rescheduleNextAlarm(nowMillis)
        changed()
        return done
    }

    private suspend fun applyStatus(
        itemId: Long,
        status: DoseStatus,
        nowMillis: Long,
        retroactive: Boolean,
        takenAtMillis: Long?,
    ): Boolean {
        val item = doseDao.byId(itemId) ?: return false
        val wasTaken = item.status == DoseStatus.TAKEN.name
        val actual = when (status) {
            DoseStatus.TAKEN -> when {
                takenAtMillis != null -> takenAtMillis
                wasTaken -> item.actualAtMillis
                retroactive -> item.scheduledAtMillis
                else -> nowMillis
            }
            DoseStatus.PENDING -> null
            else -> item.actualAtMillis
        }
        doseDao.update(
            item.copy(
                status = status.name,
                actualAtMillis = actual,
                snoozedUntilMillis = if (status == DoseStatus.SNOOZED) item.snoozedUntilMillis else null,
            )
        )
        if (status == DoseStatus.TAKEN && !wasTaken) adjustStock(item, -1)
        if (status != DoseStatus.TAKEN && wasTaken) adjustStock(item, +1)
        dismissReminder(item.id)
        return true
    }

    /**
     * Edit one dose: its quantity (only this dose) and/or its note. If the dose was
     * already taken, stock moves by the difference.
     */
    suspend fun updateDose(itemId: Long, dosageValue: Float? = null, note: String? = null) {
        val item = doseDao.byId(itemId) ?: return
        val newValue = dosageValue?.takeIf { it > 0f } ?: item.dosageValue
        // note: null = keep the current one, blank = clear it.
        val newNote = if (note == null) item.note else note.trim().ifBlank { null }
        doseDao.update(item.copy(dosageValue = newValue, note = newNote))
        if (item.status == DoseStatus.TAKEN.name && newValue != item.dosageValue) {
            val diff = units(newValue) - units(item.dosageValue)
            groupDao.byId(item.groupId)?.let { moveStock(it.medicineId, -diff) }
        }
        changed()
    }

    /**
     * Snooze a dose by [minutes]. The planned time ([DoseItemEntity.scheduledAtMillis]) is
     * the dose's identity and is never rewritten — only [DoseItemEntity.snoozedUntilMillis].
     */
    suspend fun snooze(itemId: Long, minutes: Int = 15, nowMillis: Long = System.currentTimeMillis()) =
        snoozeAll(listOf(itemId), minutes, nowMillis)

    /** Snooze several doses to the same moment, so they come back as one slot. */
    suspend fun snoozeAll(itemIds: Collection<Long>, minutes: Int = 15, nowMillis: Long = System.currentTimeMillis()) {
        val until = nowMillis + minutes * 60_000L
        val dueAts = mutableListOf<Long>()
        for (id in itemIds) {
            val item = doseDao.byId(id) ?: continue
            dueAts += item.effectiveAtMillis()
            doseDao.update(
                item.copy(
                    status = DoseStatus.SNOOZED.name,
                    snoozedUntilMillis = until,
                    snoozeCounter = item.snoozeCounter + 1,
                )
            )
            dismissReminder(item.id)
        }
        if (dueAts.isEmpty()) return
        refreshShownSlots(dueAts)
        rescheduleNextAlarm(nowMillis)
        changed()
    }

    /** The dose was answered: drop its notification and any pending "remind again". */
    private fun dismissReminder(itemId: Long) {
        alarms.cancelNag(itemId)
        Notifications.cancel(appContext, itemId)
    }

    // ------------------------------------------------------------------ stock / refill

    /** Dose quantity in stock units (fractions such as half a pill still use one unit). */
    private fun units(dosage: Float): Int = kotlin.math.ceil(dosage.toDouble()).toInt().coerceAtLeast(1)

    /** Move on-hand stock by one dose ([direction] -1 = taken, +1 = taken undone). */
    private suspend fun adjustStock(item: DoseItemEntity, direction: Int) {
        val group = groupDao.byId(item.groupId) ?: return
        moveStock(group.medicineId, direction * units(item.dosageValue))
    }

    /** Shift stock by [delta] units; notify once it crosses down to the refill threshold. */
    private suspend fun moveStock(medicineId: Long, delta: Int) {
        if (delta == 0) return
        val medicine = medicineDao.byId(medicineId) ?: return
        val stock = medicine.stockCount ?: return
        val newStock = (stock + delta).coerceAtLeast(0)
        medicineDao.update(medicine.copy(stockCount = newStock))
        val threshold = medicine.refillThreshold
        if (delta < 0 && threshold != null && stock > threshold && newStock <= threshold) {
            Notifications.showRefill(appContext, medicine.copy(stockCount = newStock))
        }
        if (threshold != null && (stock <= threshold) != (newStock <= threshold)) {
            rescheduleRefillReminder()
        }
    }

    /** "+1 box" / bought more: add [amount] units and log it in the refill history. */
    suspend fun addStock(medicineId: Long, amount: Int, nowMillis: Long = System.currentTimeMillis()) {
        val medicine = medicineDao.byId(medicineId) ?: return
        val newStock = ((medicine.stockCount ?: 0) + amount).coerceAtLeast(0)
        medicineDao.update(medicine.copy(stockCount = newStock))
        refillDao.insert(RefillEventEntity(medicineId = medicineId, atMillis = nowMillis, amount = amount, resultingStock = newStock))
        Notifications.cancelRefill(appContext, medicineId)
        rescheduleRefillReminder(nowMillis)
        changed()
    }

    /** Correct the stock to an exact count (logged as an adjustment). */
    suspend fun setStock(medicineId: Long, count: Int, nowMillis: Long = System.currentTimeMillis()) {
        val current = medicineDao.byId(medicineId)?.stockCount ?: 0
        addStock(medicineId, count.coerceAtLeast(0) - current, nowMillis)
    }

    /**
     * The day the stock runs out, walking the materialized future doses; null when the
     * medicine has no stock tracking, no scheduled doses, or the stock outlasts the window.
     */
    suspend fun estimateRunOut(medicineId: Long, nowMillis: Long = System.currentTimeMillis()): LocalDate? {
        val stock = medicineDao.byId(medicineId)?.stockCount ?: return null
        val group = groupDao.forMedicine(medicineId) ?: return null
        if (!group.active) return null
        var left = stock
        doseDao.forGroup(group.id)
            .filter {
                it.scheduledAtMillis > nowMillis &&
                    (it.status == DoseStatus.PENDING.name || it.status == DoseStatus.SNOOZED.name)
            }
            .sortedBy { it.scheduledAtMillis }
            .forEach { dose ->
                left -= units(dose.dosageValue)
                if (left < 0) return Instant.ofEpochMilli(dose.scheduledAtMillis).atZone(zone).toLocalDate()
            }
        return null
    }

    /**
     * Arm the daily refill reminder at the earliest refill time (default 09:00) among
     * running medicines whose stock is at/below the threshold; cancel when none is low.
     */
    suspend fun rescheduleRefillReminder(nowMillis: Long = System.currentTimeMillis()) {
        val low = medicineDao.lowStock()
        if (low.isEmpty()) {
            alarms.cancelRefill()
            return
        }
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val next = low.minOf { med ->
            val t = runCatching { ConsumptionTime.parse(med.refillReminderTime ?: DEFAULT_REFILL_TIME) }
                .getOrDefault(ConsumptionTime(9, 0))
            var at = now.toLocalDate().atTime(t.hour, t.minute).atZone(zone)
            if (!at.isAfter(now)) at = at.plusDays(1)
            at.toInstant().toEpochMilli()
        }
        alarms.scheduleRefill(next)
    }

    /** Medicines that currently need a refill (for the daily refill notification). */
    suspend fun lowStockMedicines(): List<MedicineEntity> = medicineDao.lowStock()

    // ------------------------------------------------------------------ misc

    private fun changed() = changes.onDataChanged()

    companion object {
        const val DEFAULT_REFILL_TIME = "09:00"
    }

    private fun DoseItemEntity.isAwaitingAnswer(now: Long): Boolean = when (status) {
        DoseStatus.PENDING.name -> true
        DoseStatus.SNOOZED.name -> (snoozedUntilMillis ?: 0L) <= now
        else -> false
    }
}
