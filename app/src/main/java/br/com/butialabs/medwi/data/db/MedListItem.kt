package br.com.butialabs.medwi.data.db

import androidx.room3.Embedded

/** Where a treatment stands, for grouping the medication list. */
enum class TreatmentState { ACTIVE, PAUSED, ENDED }

/** A medicine plus the state of its schedule. */
data class MedListItem(
    @Embedded val medicine: MedicineEntity,
    val groupId: Long?,
    val scheduleType: String?,
    val groupActive: Boolean?,
    val pausedAtMillis: Long?,
    val endedAtMillis: Long?,
) {
    val state: TreatmentState
        get() = when {
            endedAtMillis != null -> TreatmentState.ENDED
            pausedAtMillis != null -> TreatmentState.PAUSED
            else -> TreatmentState.ACTIVE
        }
}
