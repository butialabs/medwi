package br.com.butialabs.medwi.core

import br.com.butialabs.medwi.core.adherence.AdherenceCalculator
import br.com.butialabs.medwi.core.adherence.DayState
import br.com.butialabs.medwi.core.adherence.DosePoint
import br.com.butialabs.medwi.core.model.DoseStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class AdherenceCalculatorTest {

    private val zone = ZoneOffset.UTC
    private fun at(date: LocalDate, hour: Int) =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun monthly_percent_streak_and_day_states() {
        val month = YearMonth.of(2026, 1)
        // One 08:00 dose on Jan 1..10, all TAKEN except Jan 7 (SKIPPED).
        val doses = (1..10).map { dom ->
            val status = if (dom == 7) DoseStatus.SKIPPED else DoseStatus.TAKEN
            DosePoint(at(LocalDate.of(2026, 1, dom), 8), status)
        } +
            // A future dose today (Jan 10 20:00) that is not yet due.
            DosePoint(at(LocalDate.of(2026, 1, 10), 20), DoseStatus.PENDING)

        val now = at(LocalDate.of(2026, 1, 10), 12) // Jan 10, noon
        val stats = AdherenceCalculator.compute(doses, month, now, zone)

        // 10 due (Jan1..10 08:00), 9 taken -> 90%
        assertEquals(10, stats.dueTotal)
        assertEquals(9, stats.takenTotal)
        assertEquals(90, stats.percent)
        // streak: Jan10,9,8 taken; Jan7 skipped breaks -> 3
        assertEquals(3, stats.streak)
        // day states
        assertEquals(DayState.COMPLETE, stats.days[0].state)   // Jan 1
        assertEquals(DayState.MISSED, stats.days[6].state)     // Jan 7 skipped, past due
        assertEquals(DayState.FUTURE, stats.days[10].state)    // Jan 11
        assertEquals(31, stats.days.size)
    }

    @Test
    fun empty_month_is_zero_percent_no_streak() {
        val stats = AdherenceCalculator.compute(
            emptyList(), YearMonth.of(2026, 3), at(LocalDate.of(2026, 3, 15), 12), zone,
        )
        assertEquals(0, stats.percent)
        assertEquals(0, stats.streak)
        assertEquals(DayState.NONE, stats.days[0].state)
    }
}
