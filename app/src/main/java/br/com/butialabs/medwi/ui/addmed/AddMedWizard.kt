package br.com.butialabs.medwi.ui.addmed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.ui.MedViewModel

/** Identifies the medication being edited (vs. a fresh add). */
data class EditTarget(val medicineId: Long, val groupId: Long, val startEpochDay: Long)

/** Loads an existing medication into the wizard for editing. */
@Composable
fun EditMedScreen(vm: MedViewModel, medicineId: Long, onDone: () -> Unit) {
    var load by remember { mutableStateOf<Pair<MedDraft, EditTarget>?>(null) }
    LaunchedEffect(medicineId) { load = vm.editDraftFor(medicineId) }
    val loaded = load
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        AddMedWizard(vm, onDone, initial = loaded.first, editTarget = loaded.second)
    }
}

/**
 * Ordered wizard steps. TIMES drops out for "as needed"; DETAIL only appears for
 * frequencies that need extra input (doses/day now lives in the TIMES organizer).
 */
private enum class WStep { NAME, FORM, STRENGTH, APPEARANCE, FREQUENCY, DETAIL, TIMES, REVIEW }

private fun FrequencyChoice.needsDetail(): Boolean = when (this) {
    FrequencyChoice.EVERY_DAY, FrequencyChoice.ALTERNATE_DAYS, FrequencyChoice.AS_NEEDED -> false
    else -> true
}

private fun activeSteps(draft: MedDraft): List<WStep> = buildList {
    add(WStep.NAME); add(WStep.FORM); add(WStep.STRENGTH); add(WStep.APPEARANCE); add(WStep.FREQUENCY)
    if (draft.frequency.needsDetail()) add(WStep.DETAIL)
    if (draft.frequency.hasTimes) add(WStep.TIMES)
    add(WStep.REVIEW)
}

/**
 * Guided add-medication flow (one question per screen). Draft state is [rememberSaveable]
 * so it survives rotation and process death.
 */
@Composable
fun AddMedWizard(
    vm: MedViewModel,
    onDone: () -> Unit,
    initial: MedDraft = MedDraft(),
    editTarget: EditTarget? = null,
) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf(initial) }
    var index by rememberSaveable { mutableStateOf(0) }

    val steps = activeSteps(draft)
    val idx = index.coerceIn(0, steps.lastIndex)
    val current = steps[idx]
    val isLast = current == WStep.REVIEW

    val nextEnabled = when (current) {
        WStep.NAME -> draft.name.isNotBlank()
        WStep.STRENGTH -> (draft.dosageQty.parseQty() ?: 0f) > 0f
        WStep.DETAIL -> draft.frequency != FrequencyChoice.DAYS_OF_WEEK || draft.daysOfWeek.isNotEmpty()
        WStep.TIMES -> draft.times.isNotEmpty()
        else -> true
    }

    fun goBack() { if (idx == 0) onDone() else index = idx - 1 }

    fun goNext() {
        if (isLast) {
            if (editTarget != null) vm.updateMedication(editTarget, draft) { onDone() }
            else vm.addMedication(draft) { onDone() }
            return
        }
        // "Euthyrox 125mg" typed as the name -> name "Euthyrox" + strength 125 mg.
        if (current == WStep.NAME) draft = draft.withStrengthFromName()
        if (steps.getOrNull(idx + 1) == WStep.TIMES) draft = draft.withAutoTimes()
        index = idx + 1
    }

    BackHandler { goBack() }

    val title = draft.name.ifBlank { stringResource(R.string.add_med) }
    val nextLabel = if (isLast) R.string.wizard_save else R.string.wizard_next

    StepScaffold(
        title = title,
        step = idx,
        totalSteps = steps.size,
        onBack = ::goBack,
        onNext = ::goNext,
        nextEnabled = nextEnabled,
        nextLabelRes = nextLabel,
    ) {
        when (current) {
            WStep.NAME -> NameContent(draft, suggestions, vm::onQueryChange) { draft = it }
            WStep.FORM -> FormContent(draft) { draft = it }
            WStep.STRENGTH -> StrengthDoseContent(draft) { draft = it }
            WStep.APPEARANCE -> AppearanceContent(draft) { draft = it }
            WStep.FREQUENCY -> FrequencyContent(draft) { draft = it }
            WStep.DETAIL -> DetailContent(draft) { draft = it }
            WStep.TIMES -> TimesContent(draft) { draft = it }
            WStep.REVIEW -> ReviewContent(draft) { draft = it }
        }
    }
}
