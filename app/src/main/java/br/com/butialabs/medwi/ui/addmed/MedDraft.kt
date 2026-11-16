package br.com.butialabs.medwi.ui.addmed

import android.os.Parcelable
import androidx.annotation.StringRes
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseRamp
import br.com.butialabs.medwi.data.ScheduleExtras
import br.com.butialabs.medwi.data.doseRamp
import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import br.com.butialabs.medwi.ui.pill.PillColors
import br.com.butialabs.medwi.ui.pill.PillShape
import kotlinx.parcelize.Parcelize
import java.time.LocalDate

/**
 * Pharmaceutical form options, with the defaults
 * each one suggests for the dose unit, strength unit and drawing.
 */
enum class MedForm(
    val token: String,
    @StringRes val labelRes: Int,
    val defaultDoseUnit: DoseUnit,
    val defaultStrengthUnit: String,
    val defaultShape: PillShape,
) {
    TABLET("tablet", R.string.form_tablet, DoseUnit.PILL, "mg", PillShape.ROUND),
    CAPSULE("capsule", R.string.form_capsule, DoseUnit.CAPSULE, "mg", PillShape.CAPSULE),
    SOLUTION("solution", R.string.form_solution, DoseUnit.ML, "mg/ml", PillShape.BOTTLE),
    DROPS("drops", R.string.form_drops, DoseUnit.DROP, "mg/ml", PillShape.DROPPER),
    INJECTION("injection", R.string.form_injection, DoseUnit.ML, "mg/ml", PillShape.SYRINGE),
    INHALER("inhaler", R.string.form_inhaler, DoseUnit.PUFF, "mcg", PillShape.INHALER),
    CREAM("cream", R.string.form_cream, DoseUnit.APPLICATION, "%", PillShape.OINTMENT),
    PATCH("patch", R.string.form_patch, DoseUnit.PATCH, "mg", PillShape.PATCH),
    POWDER("powder", R.string.form_powder, DoseUnit.SACHET, "g", PillShape.SACHET),
    OTHER("other", R.string.form_other, DoseUnit.UNIT, "mg", PillShape.ROUND);

    companion object {
        fun fromToken(token: String?): MedForm? = entries.firstOrNull { it.token == token }
    }
}

/** Strength units offered as chips. */
val StrengthUnits = listOf("mg", "mcg", "g", "ml", "mg/ml", "mcg/ml", "UI", "%", "mEq")

/** Minutes in a day; the auto-organizer spreads doses evenly over it. */
const val MINUTES_PER_DAY = 24 * 60

/** Max doses per day offered by the organizer (every 4 h). */
const val MAX_DOSES_PER_DAY = 6

/** "Every X hours" shortcuts and the doses/day they imply (24 h / X). */
val IntervalHourChoices = listOf(24, 12, 8, 6, 4)

/**
 * Spread [count] doses [intervalMinutes] apart starting at [first], wrapping past
 * midnight, in dosing order. E.g. 08:00, 3 doses, 8 h -> 08:00, 16:00, 00:00.
 */
fun distributeTimes(first: TimeOfDay, count: Int, intervalMinutes: Int): List<TimeOfDay> {
    val start = first.minuteOfDay()
    return (0 until count.coerceAtLeast(1))
        .map { TimeOfDay.ofMinuteOfDay(start + it * intervalMinutes) }
        .distinct()
}

/** Frequency choice shown in the wizard (maps onto [ScheduleType]). */
enum class FrequencyChoice(@StringRes val labelRes: Int) {
    EVERY_DAY(R.string.freq_every_day),
    ALTERNATE_DAYS(R.string.freq_alternate_days),
    DAYS_OF_WEEK(R.string.freq_days_of_week),
    CYCLE(R.string.freq_cycle),
    EVERY_X_DAYS(R.string.freq_every_x_days),
    EVERY_X_WEEKS(R.string.freq_every_x_weeks),
    EVERY_X_MONTHS(R.string.freq_every_x_months),
    AS_NEEDED(R.string.freq_as_needed);

    /** True when this choice collects explicit dose times. */
    val hasTimes: Boolean get() = this != AS_NEEDED
}

@Parcelize
data class TimeOfDay(val hour: Int, val minute: Int) : Parcelable {
    fun toConsumptionTime() = ConsumptionTime(hour, minute)
    fun minuteOfDay(): Int = hour * 60 + minute
    override fun toString(): String = "%02d:%02d".format(hour, minute)

    companion object {
        /** Any minute count, wrapped into a single day (negative values included). */
        fun ofMinuteOfDay(minutes: Int): TimeOfDay {
            val m = Math.floorMod(minutes, MINUTES_PER_DAY)
            return TimeOfDay(m / 60, m % 60)
        }
    }
}

/**
 * Mutable draft collected across the add-medication wizard. Parcelable so it
 * survives rotation / process death via rememberSaveable. Turned into domain
 * objects by the pure [toSchedule] / [toMedicine] builders (unit-tested).
 */
@Parcelize
data class MedDraft(
    val name: String = "",
    val form: MedForm = MedForm.TABLET,
    val formOther: String = "",
    val strengthValue: String = "",
    val strengthUnit: String = "mg",
    val dosageQty: String = "1",
    val frequency: FrequencyChoice = FrequencyChoice.EVERY_DAY,
    val timesPerDay: Int = 1,
    val everyX: Int = 2,                       // EVERY_X_DAYS / WEEKS / MONTHS
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
    val cyclePillDays: Int = 21,
    val cycleBreakDays: Int = 7,
    val times: List<TimeOfDay> = listOf(TimeOfDay(8, 0)),
    /**
     * Auto-organizer on: [times] are always [timesPerDay] doses spread evenly over the
     * day (every 24/N h) from [firstTime]. Off: [times] are edited freely.
     */
    val autoSchedule: Boolean = true,
    val firstTime: TimeOfDay = TimeOfDay(8, 0),
    // ---- "Quase pronto" extras ----
    val durationDays: Int? = null,            // null = ongoing
    val instructions: String = "",
    val stockCount: String = "",
    val refillThreshold: String = "",
    val unitsPerPackage: String = "",
    val refillTime: TimeOfDay = TimeOfDay(9, 0),
    /** Drawing of the medicine: [PillShape] key + its colors. */
    val iconKey: String = PillShape.ROUND.key,
    val colorHex: String = PillColors.WHITE,
    val color2Hex: String = PillColors.WHITE,
    /** Unit of one dose ([DoseUnit] token): "1 comprimido", "10 ml", "2 gotas". */
    val doseUnit: String = DoseUnit.PILL.token,
    /** The user picked the appearance / dose unit by hand: form changes no longer reset them. */
    val appearanceTouched: Boolean = false,
    val doseUnitTouched: Boolean = false,
    /** The medicine's own reminder sound key; null = the general sound. */
    val reminderSound: String? = null,
    // ---- variable dose / taper ----
    val rampEnabled: Boolean = false,
    /** true = the dose goes down (taper), false = it goes up (titration). */
    val rampDown: Boolean = true,
    val rampAmount: String = "1",
    val rampEveryDays: Int = 7,
    val rampFinal: String = "0",
) : Parcelable {

    val dosageValue: Float get() = dosageQty.parseQty() ?: 1f

    /** Spacing between doses under the auto-organizer (24 h / doses per day). */
    val intervalMinutes: Int get() = MINUTES_PER_DAY / timesPerDay.coerceIn(1, MAX_DOSES_PER_DAY)

    /** The rule to persist with the schedule; null when times were chosen freely. */
    val doseIntervalMinutes: Int?
        get() = if (autoSchedule && frequency.hasTimes) intervalMinutes else null

    /** Recompute [times] from the rule (no-op in free mode). */
    fun withAutoTimes(): MedDraft =
        if (!autoSchedule) this
        else copy(times = distributeTimes(firstTime, timesPerDay.coerceIn(1, MAX_DOSES_PER_DAY), intervalMinutes))

    /** Change how many doses per day, keeping the first time; times are redistributed. */
    fun withDosesPerDay(count: Int): MedDraft =
        copy(timesPerDay = count.coerceIn(1, MAX_DOSES_PER_DAY)).withAutoTimes()

    /** "Every [hours] hours" -> 24/hours doses per day. */
    fun withIntervalHours(hours: Int): MedDraft = withDosesPerDay(24 / hours.coerceIn(1, 24))

    fun withFirstTime(time: TimeOfDay): MedDraft = copy(firstTime = time).withAutoTimes()

    /**
     * The user edited one time ([old] -> [new]). With the organizer on, the rule is kept
     * and every dose shifts by the same amount; in free mode only that time changes.
     */
    fun withEditedTime(old: TimeOfDay, new: TimeOfDay): MedDraft {
        if (!autoSchedule) {
            val next = times.map { if (it == old) new else it }.distinct().sortedBy { it.minuteOfDay() }
            return copy(times = next)
        }
        val delta = new.minuteOfDay() - old.minuteOfDay()
        return withFirstTime(TimeOfDay.ofMinuteOfDay(firstTime.minuteOfDay() + delta))
    }

    /** Turn the organizer on/off. Turning it on rebuilds the rule from the current times. */
    fun withAutoSchedule(enabled: Boolean): MedDraft =
        if (!enabled) copy(autoSchedule = false)
        else copy(
            autoSchedule = true,
            timesPerDay = times.size.coerceIn(1, MAX_DOSES_PER_DAY),
            firstTime = dayStartAnchor(times) ?: firstTime,
        ).withAutoTimes()

    /** The variable-dose rule, when enabled and valid for this frequency. */
    fun doseRamp(): DoseRamp? {
        if (!rampEnabled || !frequency.hasTimes) return null
        val amount = rampAmount.parseQty()?.takeIf { it > 0f } ?: return null
        val final = rampFinal.parseQty()?.takeIf { it >= 0f } ?: return null
        val step = if (rampDown) -amount else amount
        // Must move toward the final dose, otherwise there is nothing to ramp.
        if (rampDown && final >= dosageValue) return null
        if (!rampDown && final <= dosageValue) return null
        return DoseRamp(step, rampEveryDays.coerceAtLeast(1), final)
    }

    /**
     * The dose changes from [startEpochDay]: each entry is (first day, dose), ending with
     * the final dose. With a taper to 0 the last entry is (day it stops, 0).
     */
    fun rampPreview(startEpochDay: Long = LocalDate.now().toEpochDay()): List<Pair<LocalDate, Float>> {
        val ramp = doseRamp() ?: return emptyList()
        val out = mutableListOf<Pair<LocalDate, Float>>()
        var day = 0L
        var last = Float.NaN
        while (out.size < 60) {
            val dose = ramp.doseOn(dosageValue, day)
            if (dose != last) {
                out += LocalDate.ofEpochDay(startEpochDay + day) to dose
                last = dose
            }
            if (dose == ramp.final) break
            day += ramp.everyDays
        }
        return out
    }

    /** Rules persisted next to the schedule (interval organizer + variable dose). */
    fun scheduleExtras(): ScheduleExtras {
        val ramp = doseRamp()
        return ScheduleExtras(
            doseIntervalMinutes = doseIntervalMinutes,
            doseStep = ramp?.step,
            doseStepEveryDays = ramp?.everyDays,
            doseFinal = ramp?.final,
        )
    }

    /**
     * Choose the pharmaceutical form; the dose unit, strength unit and drawing follow it
     * unless the user already chose them.
     */
    fun withForm(f: MedForm): MedDraft = copy(
        form = f,
        doseUnit = if (doseUnitTouched) doseUnit else f.defaultDoseUnit.token,
        strengthUnit = if (strengthValue.isBlank()) f.defaultStrengthUnit else strengthUnit,
        iconKey = if (appearanceTouched) iconKey else f.defaultShape.key,
        colorHex = if (appearanceTouched) colorHex else f.defaultShape.defaultColor,
        color2Hex = if (appearanceTouched) color2Hex else f.defaultShape.defaultColor2,
    )

    /** Pick a drawing; its default colors apply until the user picks colors. */
    fun withShape(shape: PillShape): MedDraft = copy(
        iconKey = shape.key,
        colorHex = if (appearanceTouched) colorHex else shape.defaultColor,
        color2Hex = if (appearanceTouched) color2Hex else shape.defaultColor2,
    )

    /** Pull "125 mg" out of a name such as "Euthyrox 125mg" (only when strength is empty). */
    fun withStrengthFromName(): MedDraft {
        if (strengthValue.isNotBlank()) return this
        val parsed = parseStrength(name) ?: return this
        return copy(name = parsed.name, strengthValue = parsed.value, strengthUnit = parsed.unit)
    }

    /** Canonical form token stored on the medicine (free text for OTHER). */
    fun formToken(): String =
        if (form == MedForm.OTHER) formOther.trim().ifBlank { MedForm.OTHER.token } else form.token

    fun toMedicine(): MedicineEntity = MedicineEntity(
        name = name.trim(),
        form = formToken(),
        strengthValue = strengthValue.trim().ifBlank { null },
        // A unit without a value ("mg") means nothing; don't store it.
        strengthUnit = strengthUnit.trim().ifBlank { null }?.takeIf { strengthValue.isNotBlank() },
        color = colorHex,
        color2 = color2Hex,
        iconKey = iconKey,
        instructions = instructions.trim().ifBlank { null },
        stockCount = stockCount.trim().toIntOrNull(),
        refillThreshold = refillThreshold.trim().toIntOrNull(),
        unitsPerPackage = unitsPerPackage.trim().toIntOrNull()?.takeIf { it > 0 },
        refillReminderTime = refillTime.toString(),
        reminderSound = reminderSound,
    )

    fun toSchedule(startEpochDay: Long = LocalDate.now().toEpochDay()): Schedule {
        val cts = if (frequency == FrequencyChoice.AS_NEEDED) emptyList()
        else times.map { it.toConsumptionTime() }
        val end = durationDays?.let { startEpochDay + it - 1 }   // inclusive last day
        return when (frequency) {
            FrequencyChoice.EVERY_DAY ->
                Schedule(ScheduleType.DAILY, cts, startEpochDay, end)
            FrequencyChoice.ALTERNATE_DAYS ->
                Schedule(ScheduleType.EVERY_X_DAYS, cts, startEpochDay, end, everyXDays = 2)
            FrequencyChoice.DAYS_OF_WEEK ->
                Schedule(ScheduleType.DAYS_OF_WEEK, cts, startEpochDay, end, daysOfWeek = daysOfWeek)
            FrequencyChoice.CYCLE ->
                Schedule(
                    ScheduleType.CYCLE, cts, startEpochDay, end,
                    cyclePillDays = cyclePillDays.coerceAtLeast(1),
                    cycleBreakDays = cycleBreakDays.coerceAtLeast(0),
                )
            FrequencyChoice.EVERY_X_DAYS ->
                Schedule(ScheduleType.EVERY_X_DAYS, cts, startEpochDay, end, everyXDays = everyX.coerceAtLeast(1))
            FrequencyChoice.EVERY_X_WEEKS ->
                Schedule(ScheduleType.EVERY_X_DAYS, cts, startEpochDay, end, everyXDays = 7 * everyX.coerceAtLeast(1))
            FrequencyChoice.EVERY_X_MONTHS ->
                Schedule(ScheduleType.MONTHLY, cts, startEpochDay, end, everyXMonths = everyX.coerceAtLeast(1))
            FrequencyChoice.AS_NEEDED ->
                Schedule(ScheduleType.AS_NEEDED, emptyList(), startEpochDay, end)
        }
    }

    companion object {
        /** Rebuild a draft from persisted entities, to pre-fill the wizard for editing. */
        fun fromExisting(medicine: MedicineEntity, group: ScheduleGroupEntity): MedDraft {
            val form = MedForm.fromToken(medicine.form)
            val times = ConsumptionTime.parseList(group.consumptionTimes)
                .map { TimeOfDay(it.hour, it.minute) }
            val frequency = when (ScheduleType.valueOf(group.scheduleType)) {
                ScheduleType.DAILY -> FrequencyChoice.EVERY_DAY
                ScheduleType.DAYS_OF_WEEK -> FrequencyChoice.DAYS_OF_WEEK
                ScheduleType.CYCLE -> FrequencyChoice.CYCLE
                ScheduleType.MONTHLY -> FrequencyChoice.EVERY_X_MONTHS
                ScheduleType.AS_NEEDED -> FrequencyChoice.AS_NEEDED
                ScheduleType.EVERY_X_DAYS -> when {
                    group.everyXDays == 2 -> FrequencyChoice.ALTERNATE_DAYS
                    group.everyXDays % 7 == 0 -> FrequencyChoice.EVERY_X_WEEKS
                    else -> FrequencyChoice.EVERY_X_DAYS
                }
            }
            val everyX = when (frequency) {
                FrequencyChoice.EVERY_X_WEEKS -> group.everyXDays / 7
                FrequencyChoice.EVERY_X_MONTHS -> group.everyXMonths
                FrequencyChoice.EVERY_X_DAYS -> group.everyXDays
                else -> 2
            }
            val auto = group.doseIntervalMinutes?.let { it in 1..MINUTES_PER_DAY } == true
            val dow = group.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            val duration = group.endDateEpochDay
                ?.let { (it - group.startDateEpochDay + 1).toInt().coerceAtLeast(1) }
            val qty = group.dosageValue.qtyText()
            val ramp = group.doseRamp()
            return MedDraft(
                name = medicine.name,
                form = form ?: MedForm.OTHER,
                formOther = if (form == null) (medicine.form ?: "") else "",
                strengthValue = medicine.strengthValue ?: "",
                strengthUnit = medicine.strengthUnit ?: "mg",
                dosageQty = qty,
                frequency = frequency,
                timesPerDay = if (auto) (MINUTES_PER_DAY / group.doseIntervalMinutes!!).coerceIn(1, MAX_DOSES_PER_DAY)
                    else times.size.coerceIn(1, MAX_DOSES_PER_DAY),
                everyX = everyX.coerceAtLeast(1),
                daysOfWeek = dow.ifEmpty { setOf(1, 2, 3, 4, 5) },
                cyclePillDays = if (group.cyclePillDays > 0) group.cyclePillDays else 21,
                cycleBreakDays = group.cycleBreakDays,
                times = times.ifEmpty { listOf(TimeOfDay(8, 0)) },
                autoSchedule = auto,
                firstTime = dayStartAnchor(times) ?: TimeOfDay(8, 0),
                durationDays = duration,
                instructions = medicine.instructions ?: "",
                stockCount = medicine.stockCount?.toString() ?: "",
                refillThreshold = medicine.refillThreshold?.toString() ?: "",
                unitsPerPackage = medicine.unitsPerPackage?.toString() ?: "",
                refillTime = medicine.refillReminderTime
                    ?.let { runCatching { ConsumptionTime.parse(it) }.getOrNull() }
                    ?.let { TimeOfDay(it.hour, it.minute) } ?: TimeOfDay(9, 0),
                iconKey = PillShape.fromKey(medicine.iconKey).key,
                colorHex = medicine.color ?: PillShape.fromKey(medicine.iconKey).defaultColor,
                color2Hex = medicine.color2 ?: PillShape.fromKey(medicine.iconKey).defaultColor2,
                doseUnit = group.dosageUnit,
                appearanceTouched = true,
                doseUnitTouched = true,
                reminderSound = medicine.reminderSound,
                rampEnabled = ramp != null,
                rampDown = ramp?.let { it.step < 0 } ?: true,
                rampAmount = ramp?.let { kotlin.math.abs(it.step).qtyText() } ?: "1",
                rampEveryDays = ramp?.everyDays ?: 7,
                rampFinal = ramp?.final?.qtyText() ?: "0",
            )
        }
    }
}

/** "1,5" or "1.5" -> 1.5f. */
fun String.parseQty(): Float? = trim().replace(',', '.').toFloatOrNull()

/** 2f -> "2", 1.5f -> "1.5". */
fun Float.qtyText(): String = if (this == toLong().toFloat()) toLong().toString() else toString()

/**
 * The dose that opens the user's day: the first one from 04:00 on. With wrap-around
 * schedules (08:00 / 16:00 / 00:00) this picks 08:00 rather than midnight.
 */
fun dayStartAnchor(times: List<TimeOfDay>): TimeOfDay? =
    times.minByOrNull { Math.floorMod(it.minuteOfDay() - 4 * 60, MINUTES_PER_DAY) }
