package br.com.butialabs.medwi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.butialabs.medwi.core.adherence.AdherenceCalculator
import br.com.butialabs.medwi.core.adherence.AdherenceStats
import br.com.butialabs.medwi.core.adherence.DosePoint
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.catalog.MedCatalog
import br.com.butialabs.medwi.data.db.AsNeededMed
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.data.db.MedListItem
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.RefillEventEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import br.com.butialabs.medwi.data.db.TreatmentState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import br.com.butialabs.medwi.ui.addmed.EditTarget
import br.com.butialabs.medwi.ui.addmed.MedDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MedViewModel @Inject constructor(
    private val repo: MedRepository,
    private val catalog: MedCatalog,
) : ViewModel() {

    init {
        // Rebuild future doses and alarms on every app start: covers restores from the
        // Android backup (alarms are never restored) and alarms dropped by a force-stop.
        viewModelScope.launch { repo.regenerateAll() }
    }

    /** The day whose doses are shown (defaults to today). */
    private val selectedDay = MutableStateFlow(LocalDate.now())

    /** Today's doses; null until the first database read (avoids flashing the empty state). */
    val doses: StateFlow<List<DoseView>?> =
        selectedDay.flatMapLatest(::dosesOn)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-anchor "today" (e.g. when the app is resumed after midnight). */
    fun refreshToday() { selectedDay.value = LocalDate.now() }

    private fun dosesOn(day: LocalDate) = run {
        val zone = ZoneId.systemDefault()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        repo.observeDoses(from, to)
    }

    val asNeeded: StateFlow<List<AsNeededMed>> =
        repo.observeAsNeeded().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun logExtraDose(groupId: Long) = viewModelScope.launch { repo.logExtraDose(groupId) }

    // ---- progress / adherence ----
    private val selectedMonth = MutableStateFlow(YearMonth.now())

    val monthStats: StateFlow<AdherenceStats> =
        selectedMonth.flatMapLatest { ym ->
            val zone = ZoneId.systemDefault()
            // Load from 45 days before the month so the streak can reach back.
            val from = ym.atDay(1).minusDays(45).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = ym.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            repo.observeDoses(from, to).map { views ->
                val now = System.currentTimeMillis()
                val points = views.map { DosePoint(it.scheduledAtMillis, DoseStatus.valueOf(it.status)) }
                AdherenceCalculator.compute(points, ym, now, zone)
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AdherenceStats(YearMonth.now(), 0, 0, 0, 0, emptyList()),
        )

    fun showPreviousMonth() = showMonth(selectedMonth.value.minusMonths(1))
    fun showNextMonth() = showMonth(selectedMonth.value.plusMonths(1))

    /** Switching month selects today if it's in that month, else its first day. */
    private fun showMonth(ym: YearMonth) {
        selectedMonth.value = ym
        val today = LocalDate.now()
        progressDay.value = if (YearMonth.from(today) == ym) today else ym.atDay(1)
    }

    /** Day tapped in the Progress calendar; its doses are listed below it. */
    private val progressDay = MutableStateFlow(LocalDate.now())
    val selectedProgressDay: StateFlow<LocalDate> = progressDay

    val progressDayDoses: StateFlow<List<DoseView>> =
        progressDay.flatMapLatest(::dosesOn)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectProgressDay(day: LocalDate) { progressDay.value = day }

    /**
     * Correct a dose from the Progress history: TAKEN / SKIPPED, or PENDING to undo.
     * A dose from a past day marked taken is recorded at its planned time.
     */
    fun setDoseStatus(dose: DoseView, status: DoseStatus) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        repo.markDose(dose.itemId, status, nowMillis = now, retroactive = dose.scheduledAtMillis < startOfToday())
    }

    private fun startOfToday(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    // ---- autocomplete ----
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    fun onQueryChange(query: String) {
        viewModelScope.launch {
            _suggestions.value = catalog.search(query)
        }
    }

    /** Persist a finished wizard draft and arm its first alarm. */
    fun addMedication(draft: MedDraft, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.addMedication(
                medicine = draft.toMedicine(),
                dosageValue = draft.dosageValue,
                dosageUnit = draft.doseUnit,
                schedule = draft.toSchedule(),
                extras = draft.scheduleExtras(),
            )
            onDone()
        }
    }

    /** Load an existing medication into a wizard draft for editing. */
    suspend fun editDraftFor(medicineId: Long): Pair<MedDraft, EditTarget>? {
        val (med, group) = repo.medicineWithGroup(medicineId) ?: return null
        return MedDraft.fromExisting(med, group) to
            EditTarget(med.id, group.id, group.startDateEpochDay)
    }

    /** Apply wizard edits to an existing medication, keeping its original start date. */
    fun updateMedication(target: EditTarget, draft: MedDraft, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.updateMedication(
                medicineId = target.medicineId,
                groupId = target.groupId,
                medicine = draft.toMedicine(),
                dosageValue = draft.dosageValue,
                dosageUnit = draft.doseUnit,
                schedule = draft.toSchedule(startEpochDay = target.startEpochDay),
                extras = draft.scheduleExtras(),
            )
            onDone()
        }
    }

    fun deleteMedicine(medicineId: Long) = viewModelScope.launch { repo.deleteMedicine(medicineId) }

    fun take(itemId: Long) = viewModelScope.launch { repo.markDose(itemId, DoseStatus.TAKEN) }
    fun skip(itemId: Long) = viewModelScope.launch { repo.markDose(itemId, DoseStatus.SKIPPED) }
    fun snooze(itemId: Long) = viewModelScope.launch { repo.snooze(itemId, 15) }

    /** "Take all" for one time slot. */
    fun takeAll(itemIds: List<Long>) = viewModelScope.launch { repo.markDoses(itemIds, DoseStatus.TAKEN) }

    /** "I took it at …": records the real time instead of now. */
    fun takeAt(itemId: Long, takenAtMillis: Long) =
        viewModelScope.launch { repo.markDose(itemId, DoseStatus.TAKEN, takenAtMillis = takenAtMillis) }

    /** Change this dose's quantity and/or note (null = keep, blank note = clear). */
    fun updateDose(itemId: Long, dosageValue: Float?, note: String?) =
        viewModelScope.launch { repo.updateDose(itemId, dosageValue, note) }

    // ---- medication list / detail ----

    val medList: StateFlow<List<MedListItem>> =
        repo.observeMedList().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything the medication detail screen shows, kept live. */
    fun medDetail(medicineId: Long): Flow<MedDetail?> =
        combine(
            repo.observeMedicine(medicineId),
            repo.observeGroupFor(medicineId),
            repo.observeRefills(medicineId),
        ) { med, group, refills ->
            med?.let { MedDetail(it, group, refills, repo.estimateRunOut(medicineId)) }
        }

    fun logDoseNow(groupId: Long) = viewModelScope.launch { repo.logExtraDose(groupId) }
    fun pause(groupId: Long) = viewModelScope.launch { repo.pause(groupId) }
    fun resume(groupId: Long) = viewModelScope.launch { repo.resume(groupId) }
    fun end(groupId: Long) = viewModelScope.launch { repo.end(groupId) }
    fun addStock(medicineId: Long, amount: Int) = viewModelScope.launch { repo.addStock(medicineId, amount) }
    fun setStock(medicineId: Long, count: Int) = viewModelScope.launch { repo.setStock(medicineId, count) }
}

/** Medication detail: the medicine, its schedule, refill history and run-out estimate. */
data class MedDetail(
    val medicine: MedicineEntity,
    val group: ScheduleGroupEntity?,
    val refills: List<RefillEventEntity>,
    val runsOutOn: LocalDate?,
) {
    val state: TreatmentState
        get() = when {
            group?.endedAtMillis != null -> TreatmentState.ENDED
            group?.pausedAtMillis != null -> TreatmentState.PAUSED
            else -> TreatmentState.ACTIVE
        }
}
