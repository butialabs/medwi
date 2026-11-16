package br.com.butialabs.medwi.core.adherence

import br.com.butialabs.medwi.core.model.DoseStatus
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** A single dose instance reduced to what adherence math needs. */
data class DosePoint(val scheduledAtMillis: Long, val status: DoseStatus)

enum class DayState { COMPLETE, PARTIAL, MISSED, NONE, FUTURE }

data class DayAdherence(val date: LocalDate, val due: Int, val taken: Int, val state: DayState)

data class AdherenceStats(
    val month: YearMonth,
    val percent: Int,
    val takenTotal: Int,
    val dueTotal: Int,
    val streak: Int,
    val days: List<DayAdherence>, // one per day of [month]
)

/**
 * Pure adherence math (no Android): monthly adherence and the current streak.
 *
 * A dose counts as *due* once its scheduled time has passed. Monthly adherence is
 * taken/due across the month; the streak is consecutive fully-taken days ending at
 * today (today still in progress does not break it).
 */
object AdherenceCalculator {

    fun compute(
        doses: List<DosePoint>,
        month: YearMonth,
        nowMillis: Long,
        zone: ZoneId,
    ): AdherenceStats {
        fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = dateOf(nowMillis)

        // Bucket due/taken counts per local date.
        val byDate = HashMap<LocalDate, IntArray>() // [due, taken]
        for (d in doses) {
            if (d.scheduledAtMillis > nowMillis) continue // not yet due
            val arr = byDate.getOrPut(dateOf(d.scheduledAtMillis)) { IntArray(2) }
            arr[0]++
            if (d.status == DoseStatus.TAKEN) arr[1]++
        }

        val days = ArrayList<DayAdherence>(month.lengthOfMonth())
        var dueTotal = 0
        var takenTotal = 0
        for (dom in 1..month.lengthOfMonth()) {
            val date = month.atDay(dom)
            val arr = byDate[date]
            val due = arr?.get(0) ?: 0
            val taken = arr?.get(1) ?: 0
            dueTotal += due
            takenTotal += taken
            val state = when {
                date.isAfter(today) -> DayState.FUTURE
                due == 0 -> DayState.NONE
                taken >= due -> DayState.COMPLETE
                taken == 0 -> DayState.MISSED
                else -> DayState.PARTIAL
            }
            days += DayAdherence(date, due, taken, state)
        }
        val percent = if (dueTotal == 0) 0 else (takenTotal * 100) / dueTotal

        // Streak: consecutive fully-taken days ending at today. Today still in
        // progress (due but not all taken) does not break it — start at yesterday.
        var streak = 0
        var cursor = today
        byDate[today]?.let { if (it[0] > 0 && it[1] < it[0]) cursor = today.minusDays(1) }
        var guard = 0
        while (guard++ < 400) {
            val arr = byDate[cursor]
            val due = arr?.get(0) ?: 0
            val taken = arr?.get(1) ?: 0
            if (due == 0) {
                cursor = cursor.minusDays(1)
                if (cursor.isBefore(month.atDay(1).minusDays(90))) break
                continue
            }
            if (taken >= due) {
                streak++
                cursor = cursor.minusDays(1)
            } else break
        }

        return AdherenceStats(month, percent, takenTotal, dueTotal, streak, days)
    }
}
