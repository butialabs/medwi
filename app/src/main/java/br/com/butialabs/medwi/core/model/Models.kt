package br.com.butialabs.medwi.core.model

/**
 * Pure-Kotlin domain model for the scheduling engine.
 * No Android dependencies, so it can be unit-tested in isolation.
 */

/** How a medication recurs. */
enum class ScheduleType {
    /** N fixed times every day. */
    DAILY,

    /** Every X days (X = [Schedule.everyXDays]). */
    EVERY_X_DAYS,

    /** On specific weekdays ([Schedule.daysOfWeek], ISO 1=Mon..7=Sun). */
    DAYS_OF_WEEK,

    /** Take for `cyclePillDays`, then pause `cycleBreakDays`, repeat. */
    CYCLE,

    /** Every X calendar months (X = [Schedule.everyXMonths]), on the start day-of-month. */
    MONTHLY,

    /** No reminders; taken only when needed. */
    AS_NEEDED,
}

/** A time of day a dose is due. */
data class ConsumptionTime(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "hour out of range: $hour" }
        require(minute in 0..59) { "minute out of range: $minute" }
    }

    override fun toString(): String = "%02d:%02d".format(hour, minute)

    companion object {
        /** Parse "08:00" or a CSV "08:00,20:00" into times. */
        fun parse(value: String): ConsumptionTime {
            val (h, m) = value.trim().split(":").map { it.toInt() }
            return ConsumptionTime(h, m)
        }

        fun parseList(csv: String): List<ConsumptionTime> =
            csv.split(",").mapNotNull { it.trim().takeIf(String::isNotEmpty) }.map(::parse)
    }
}

/**
 * The recurrence policy (when a schedule is active and its parameters).
 *
 * @param startDateEpochDay first day the schedule is active (epoch day).
 * @param endDateEpochDay optional last active day (inclusive).
 * @param everyXDays interval for [ScheduleType.EVERY_X_DAYS].
 * @param daysOfWeek ISO weekdays (1=Mon..7=Sun) for [ScheduleType.DAYS_OF_WEEK].
 * @param cyclePillDays / cycleBreakDays for [ScheduleType.CYCLE].
 */
data class Schedule(
    val type: ScheduleType,
    val consumptionTimes: List<ConsumptionTime>,
    val startDateEpochDay: Long,
    val endDateEpochDay: Long? = null,
    val everyXDays: Int = 1,
    val daysOfWeek: Set<Int> = emptySet(),
    val cyclePillDays: Int = 0,
    val cycleBreakDays: Int = 0,
    val everyXMonths: Int = 1,
) {
    init {
        if (type == ScheduleType.EVERY_X_DAYS) require(everyXDays >= 1)
        if (type == ScheduleType.CYCLE) require(cyclePillDays >= 1 && cycleBreakDays >= 0)
        if (type == ScheduleType.MONTHLY) require(everyXMonths >= 1)
    }
}

/**
 * Variable dose (taper / titration): starting from the group's dosage, the dose
 * changes by [step] (negative = taper down) every [everyDays] days, stopping at [final].
 * Tapering down to 0 ends the treatment: no doses are generated once it reaches 0.
 */
data class DoseRamp(val step: Float, val everyDays: Int, val final: Float) {
    init {
        require(step != 0f) { "ramp step must not be 0" }
        require(everyDays >= 1) { "ramp interval must be >= 1 day" }
        require(final >= 0f) { "final dose must be >= 0" }
    }

    /** Dose on the day that is [daysSinceStart] days after the schedule start. */
    fun doseOn(start: Float, daysSinceStart: Long): Float {
        val steps = (daysSinceStart.coerceAtLeast(0) / everyDays).toFloat()
        val raw = start + step * steps
        return if (step < 0) maxOf(raw, final) else minOf(raw, final)
    }
}

/** The "recipe" for one medication's dosing. */
data class MedGroup(
    val id: Long,
    val medicineId: Long,
    val dosageValue: Float,
    val dosageUnit: String,
    val schedule: Schedule,
    val active: Boolean = true,
    val doseRamp: DoseRamp? = null,
)

/** Status of a single generated dose. */
enum class DoseStatus { PENDING, TAKEN, SKIPPED, SNOOZED, MISSED }

/** A concrete dose instance — one reminder. */
data class DoseItem(
    val groupId: Long,
    val scheduledAtMillis: Long,
    val dosageValue: Float,
    val dosageUnit: String,
    val status: DoseStatus = DoseStatus.PENDING,
    /** Stable identity within a group = its planned time; used to merge regenerations. */
    val key: Long = scheduledAtMillis,
)
