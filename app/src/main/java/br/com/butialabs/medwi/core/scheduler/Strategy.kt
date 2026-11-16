package br.com.butialabs.medwi.core.scheduler

import br.com.butialabs.medwi.core.model.Schedule
import br.com.butialabs.medwi.core.model.ScheduleType
import java.time.LocalDate

/**
 * A recurrence strategy decides whether a given day is a "dose day"; one per
 * [ScheduleType].
 */
interface Strategy {
    /** True if [date] is an active dose day for [schedule]. */
    fun occursOn(date: LocalDate, schedule: Schedule): Boolean

    companion object {
        fun forType(type: ScheduleType): Strategy = when (type) {
            ScheduleType.DAILY -> DailyStrategy
            ScheduleType.EVERY_X_DAYS -> EveryXDaysStrategy
            ScheduleType.DAYS_OF_WEEK -> DaysOfWeekStrategy
            ScheduleType.CYCLE -> CycleStrategy
            ScheduleType.MONTHLY -> MonthlyStrategy
            ScheduleType.AS_NEEDED -> AsNeededStrategy
        }
    }
}

/** Every day. */
object DailyStrategy : Strategy {
    override fun occursOn(date: LocalDate, schedule: Schedule): Boolean = true
}

/**
 * Every X days counted from the start date.
 * (`dayIndex % everyXDays == 0`.)
 */
object EveryXDaysStrategy : Strategy {
    override fun occursOn(date: LocalDate, schedule: Schedule): Boolean {
        val diff = date.toEpochDay() - schedule.startDateEpochDay
        if (diff < 0) return false
        val step = schedule.everyXDays.coerceAtLeast(1)
        return diff % step == 0L
    }
}

/** On the configured ISO weekdays (1=Mon..7=Sun). */
object DaysOfWeekStrategy : Strategy {
    override fun occursOn(date: LocalDate, schedule: Schedule): Boolean =
        date.dayOfWeek.value in schedule.daysOfWeek
}

/**
 * Cyclic: take for `cyclePillDays`, pause `cycleBreakDays`, repeat.
 * (E.g. contraceptives: 21 days on, 7 off.)
 */
object CycleStrategy : Strategy {
    override fun occursOn(date: LocalDate, schedule: Schedule): Boolean {
        val diff = date.toEpochDay() - schedule.startDateEpochDay
        if (diff < 0) return false
        val period = schedule.cyclePillDays + schedule.cycleBreakDays
        if (period <= 0) return false
        return (diff % period) < schedule.cyclePillDays
    }
}

/**
 * Every X calendar months, anchored on the start date's day-of-month. A dose
 * falls on the anchor day; when a month is too short (e.g. the 31st in February),
 * it clamps to that month's last day.
 */
object MonthlyStrategy : Strategy {
    override fun occursOn(date: LocalDate, schedule: Schedule): Boolean {
        val start = LocalDate.ofEpochDay(schedule.startDateEpochDay)
        if (date.isBefore(start)) return false
        val step = schedule.everyXMonths.coerceAtLeast(1)
        val monthsBetween =
            (date.year - start.year) * 12 + (date.monthValue - start.monthValue)
        if (monthsBetween < 0 || monthsBetween % step != 0) return false
        // Anchor day clamped to this month's length (handles 29/30/31 in short months).
        val anchorDay = minOf(start.dayOfMonth, date.lengthOfMonth())
        return date.dayOfMonth == anchorDay
    }
}

/** Never auto-generates reminders. */
object AsNeededStrategy : Strategy {
    override fun occursOn(date: LocalDate, schedule: Schedule): Boolean = false
}
