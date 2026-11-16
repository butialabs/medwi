package br.com.butialabs.medwi.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.core.scheduler.Scheduler
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.ScheduleExtras
import br.com.butialabs.medwi.data.db.AppDatabase
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.RefillEventEntity
import br.com.butialabs.medwi.data.toCore
import br.com.butialabs.medwi.data.toEntity
import br.com.butialabs.medwi.ui.pill.PillColors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import kotlin.random.Random

/**
 * Debug-only: replaces all data with a fictional demo set (store screenshots, manual QA).
 *
 *   adb shell am broadcast -n br.com.butialabs.medwi/.debug.DemoDataReceiver --es lang pt
 *   adb shell am broadcast -n br.com.butialabs.medwi/.debug.DemoDataReceiver --ez notify true
 *
 * "lang" picks en/pt names; "notify" posts the reminders of the next time slot.
 */
@AndroidEntryPoint
class DemoDataReceiver : BroadcastReceiver() {

    @Inject lateinit var db: AppDatabase
    @Inject lateinit var repo: MedRepository
    @Inject lateinit var scheduler: Scheduler

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (intent.getBooleanExtra("notify", false)) notifyNextSlot()
                else seed(intent.getStringExtra("lang") ?: "en")
            } catch (e: Exception) {
                Log.e(TAG, "demo data failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private data class Demo(
        val en: String,
        val pt: String,
        val form: String,
        val strength: String?,
        val strengthUnit: String?,
        val dose: Float,
        val unit: String,
        val shape: String,
        val color: String,
        val color2: String? = null,
        val times: List<String>,
        val type: ScheduleType = ScheduleType.DAILY,
        val startDaysAgo: Long = 45,
        val durationDays: Long? = null,
        val daysOfWeek: Set<Int> = emptySet(),
        val intervalMinutes: Int? = null,
        val stock: Int? = null,
        val threshold: Int? = null,
        val box: Int? = null,
        val instructionsEn: String? = null,
        val instructionsPt: String? = null,
        val state: String = "active",
    )

    private val demo = listOf(
        Demo("Losartan", "Losartana", "tablet", "50", "mg", 1f, "pill", "round", PillColors.WHITE,
            times = listOf("08:00", "20:00"), stock = 24, threshold = 10, box = 30, intervalMinutes = 720),
        Demo("Levothyroxine", "Levotiroxina", "tablet", "125", "mcg", 1f, "pill", "round_scored", PillColors.LIGHT_BLUE,
            times = listOf("07:00"), stock = 41, threshold = 7, box = 30,
            instructionsEn = "Empty stomach, 30 min before breakfast", instructionsPt = "Em jejum, 30 min antes do café"),
        Demo("Omeprazole", "Omeprazol", "capsule", "20", "mg", 1f, "capsule", "capsule", PillColors.PURPLE, PillColors.PINK,
            times = listOf("08:00"), stock = 9, threshold = 10, box = 28),
        Demo("Amoxicillin", "Amoxicilina", "solution", "250", "mg/5ml", 5f, "ml", "bottle", PillColors.AMBER, PillColors.WHITE,
            times = listOf("06:00", "14:00", "22:00"), startDaysAgo = 2, durationDays = 7, intervalMinutes = 480),
        Demo("Vitamin D3", "Vitamina D3", "capsule", "7000", "IU", 1f, "capsule", "softgel", PillColors.YELLOW,
            times = listOf("12:00"), type = ScheduleType.DAYS_OF_WEEK, daysOfWeek = setOf(7)),
        Demo("Metamizole", "Dipirona", "drops", "500", "mg/ml", 20f, "drop", "dropper", PillColors.AMBER, PillColors.WHITE,
            times = emptyList(), type = ScheduleType.AS_NEEDED),
        Demo("Ibuprofen", "Ibuprofeno", "tablet", "400", "mg", 1f, "pill", "oblong", PillColors.ORANGE,
            times = listOf("09:00", "21:00"), startDaysAgo = 20, state = "paused"),
        Demo("Azithromycin", "Azitromicina", "tablet", "500", "mg", 1f, "pill", "oval", PillColors.WHITE,
            times = listOf("10:00"), startDaysAgo = 30, state = "ended"),
    )

    private suspend fun seed(lang: String) {
        val pt = lang.startsWith("pt")
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val rnd = Random(7)

        db.medicineDao().deleteAll()

        for (d in demo) {
            val start = today.minusDays(d.startDaysAgo)
            val schedule = Schedule(
                type = d.type,
                consumptionTimes = d.times.map(ConsumptionTime::parse),
                startDateEpochDay = start.toEpochDay(),
                endDateEpochDay = d.durationDays?.let { start.plusDays(it - 1).toEpochDay() },
                daysOfWeek = d.daysOfWeek,
            )
            val medicine = MedicineEntity(
                name = if (pt) d.pt else d.en,
                form = d.form,
                strengthValue = d.strength,
                strengthUnit = if (pt && d.strengthUnit == "IU") "UI" else d.strengthUnit,
                color = d.color,
                color2 = d.color2,
                iconKey = d.shape,
                instructions = if (pt) d.instructionsPt else d.instructionsEn,
                stockCount = d.stock,
                refillThreshold = d.threshold,
                unitsPerPackage = d.box,
                refillReminderTime = "09:00",
            )
            val groupId = repo.addMedication(
                medicine, d.dose, d.unit, schedule, now, ScheduleExtras(doseIntervalMinutes = d.intervalMinutes),
            )

            // Past history: mostly taken, a few skipped or missed.
            val group = db.scheduleGroupDao().byId(groupId)!!.toCore()
            val stopAt = when (d.state) {
                "paused" -> now - 6 * Scheduler.ONE_DAY_MILLIS
                "ended" -> now - 10 * Scheduler.ONE_DAY_MILLIS
                else -> now
            }
            val past = scheduler.generate(group, start.atStartOfDay(zone).toInstant().toEpochMilli(), stopAt)
                .filter { it.scheduledAtMillis < now }
                .map { item ->
                    val roll = rnd.nextInt(100)
                    val isToday = java.time.Instant.ofEpochMilli(item.scheduledAtMillis).atZone(zone).toLocalDate() == today
                    val status = when {
                        isToday -> DoseStatus.TAKEN
                        roll < 4 -> DoseStatus.SKIPPED
                        roll < 9 -> DoseStatus.PENDING // shows as "not logged"
                        else -> DoseStatus.TAKEN
                    }
                    val e = item.copy(status = status).toEntity()
                    if (status == DoseStatus.TAKEN) e.copy(actualAtMillis = item.scheduledAtMillis + rnd.nextLong(0, 25) * 60_000L)
                    else e
                }
            if (past.isNotEmpty()) {
                val existing = db.doseItemDao().forGroup(groupId).associateBy { it.scheduledAtMillis }
                db.doseItemDao().upsertAll(past.map { p -> existing[p.scheduledAtMillis]?.let { p.copy(id = it.id) } ?: p })
            }

            when (d.state) {
                "paused" -> repo.pause(groupId, stopAt)
                "ended" -> repo.end(groupId, stopAt)
            }
        }

        // Refill history for the first medicine, and a note on today's levothyroxine.
        val losartanId = db.medicineDao().all().first { it.name == (if (pt) "Losartana" else "Losartan") }.id
        val day = Scheduler.ONE_DAY_MILLIS
        listOf(52L to 30, 27L to 30).forEach { (ago, amount) ->
            db.refillEventDao().insert(
                RefillEventEntity(medicineId = losartanId, atMillis = now - ago * day, amount = amount, resultingStock = amount + 6),
            )
        }
        repo.regenerateAll(now)
        Log.i(TAG, "demo data ready ($lang) at ${LocalTime.now()}")
    }

    private suspend fun notifyNextSlot() {
        val next = db.doseItemDao().nextPending(System.currentTimeMillis()) ?: return
        repo.showReminders(next.scheduledAtMillis, repo.dosesDueAt(next.scheduledAtMillis), silent = false)
    }

    private companion object {
        const val TAG = "DemoData"
    }
}
