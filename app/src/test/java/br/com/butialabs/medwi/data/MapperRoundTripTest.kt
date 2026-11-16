package br.com.butialabs.medwi.data

import br.com.butialabs.medwi.core.model.ConsumptionTime
import br.com.butialabs.medwi.core.model.DoseItem
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.core.model.ScheduleType
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM round-trip checks for the entity <-> core-model mappers. */
class MapperRoundTripTest {

    @Test
    fun scheduleGroupEntity_toCore_parses_all_flattened_fields() {
        val entity = ScheduleGroupEntity(
            id = 42,
            medicineId = 7,
            dosageValue = 2f,
            dosageUnit = "ml",
            scheduleType = "DAYS_OF_WEEK",
            consumptionTimes = "08:00,20:30",
            startDateEpochDay = 100,
            endDateEpochDay = 200,
            everyXDays = 1,
            daysOfWeek = "1,3,5",
            cyclePillDays = 0,
            cycleBreakDays = 0,
            everyXMonths = 3,
            active = true,
        )

        val core = entity.toCore()

        assertEquals(42L, core.id)
        assertEquals(7L, core.medicineId)
        assertEquals("ml", core.dosageUnit)
        assertEquals(ScheduleType.DAYS_OF_WEEK, core.schedule.type)
        assertEquals(
            listOf(ConsumptionTime(8, 0), ConsumptionTime(20, 30)),
            core.schedule.consumptionTimes,
        )
        assertEquals(setOf(1, 3, 5), core.schedule.daysOfWeek)
        assertEquals(100L, core.schedule.startDateEpochDay)
        assertEquals(200L, core.schedule.endDateEpochDay)
        assertEquals(3, core.schedule.everyXMonths)
    }

    @Test
    fun doseItem_toEntity_then_toCore_preserves_fields_and_id() {
        val item = DoseItem(
            groupId = 9,
            scheduledAtMillis = 123_456,
            dosageValue = 1.5f,
            dosageUnit = "pill",
            status = DoseStatus.TAKEN,
        )

        val entity = item.toEntity(existingId = 55)
        assertEquals(55L, entity.id)
        assertEquals("TAKEN", entity.status)

        val back = entity.toCore()
        assertEquals(item.groupId, back.groupId)
        assertEquals(item.scheduledAtMillis, back.scheduledAtMillis)
        assertEquals(item.dosageValue, back.dosageValue, 0.0001f)
        assertEquals(item.dosageUnit, back.dosageUnit)
        assertEquals(DoseStatus.TAKEN, back.status)
        assertEquals(123_456L, back.key)
    }
}
