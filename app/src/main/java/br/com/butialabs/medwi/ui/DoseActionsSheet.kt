package br.com.butialabs.medwi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.ui.addmed.TimeOfDay
import br.com.butialabs.medwi.ui.addmed.TimePickerDialog
import br.com.butialabs.medwi.ui.addmed.formLabel
import br.com.butialabs.medwi.ui.addmed.parseQty
import br.com.butialabs.medwi.ui.addmed.qtyText
import java.time.Instant
import java.time.ZoneId

/**
 * "More" actions for one dose: record the real time it
 * was taken, change this dose's quantity, add a note, skip/snooze, or undo an answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoseActionsSheet(dose: DoseView, vm: MedViewModel, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val planned = Instant.ofEpochMilli(dose.scheduledAtMillis).atZone(zone)
    var dialog by remember { mutableStateOf<DoseDialog?>(null) }
    val state = dose.uiState()
    val answered = state == DoseUiState.TAKEN || state == DoseUiState.SKIPPED

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                "${planned.toLocalTime().toString().take(5)} · ${medTitle(dose.medicineName, dose.strengthValue, dose.strengthUnit)}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                doseText(dose.dosageValue, dose.dosageUnit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            SheetRow(Icons.Outlined.Schedule, stringResource(R.string.dose_taken_at_pick)) { dialog = DoseDialog.TIME }
            SheetRow(Icons.Outlined.Numbers, stringResource(R.string.dose_quantity)) { dialog = DoseDialog.QUANTITY }
            SheetRow(Icons.Outlined.EditNote, stringResource(R.string.dose_note)) { dialog = DoseDialog.NOTE }
            if (!answered) {
                SheetRow(Icons.Outlined.Snooze, stringResource(R.string.action_snooze)) {
                    vm.snooze(dose.itemId); onDismiss()
                }
                SheetRow(Icons.Outlined.Block, stringResource(R.string.action_skip)) {
                    vm.skip(dose.itemId); onDismiss()
                }
            } else {
                SheetRow(Icons.Outlined.Undo, stringResource(R.string.action_undo)) {
                    vm.setDoseStatus(dose, DoseStatus.PENDING); onDismiss()
                }
            }
        }
    }

    when (dialog) {
        DoseDialog.TIME -> {
            val initial = dose.actualAtMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: planned
            TimePickerDialog(
                initial = TimeOfDay(initial.hour, initial.minute),
                onConfirm = { t ->
                    val at = planned.toLocalDate().atTime(t.hour, t.minute).atZone(zone).toInstant().toEpochMilli()
                    vm.takeAt(dose.itemId, at)
                    dialog = null
                    onDismiss()
                },
                onDismiss = { dialog = null },
            )
        }
        DoseDialog.QUANTITY -> TextDialog(
            title = stringResource(R.string.dose_quantity),
            initial = dose.dosageValue.qtyText(),
            numeric = true,
            valid = { (it.parseQty() ?: 0f) > 0f },
            onConfirm = { vm.updateDose(dose.itemId, it.parseQty(), null); dialog = null; onDismiss() },
            onDismiss = { dialog = null },
        )
        DoseDialog.NOTE -> TextDialog(
            title = stringResource(R.string.dose_note),
            initial = dose.note.orEmpty(),
            placeholder = stringResource(R.string.dose_note_hint),
            onConfirm = { vm.updateDose(dose.itemId, null, it); dialog = null; onDismiss() },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private enum class DoseDialog { TIME, QUANTITY, NOTE }

@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

@Composable
private fun TextDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    numeric: Boolean = false,
    placeholder: String? = null,
    valid: (String) -> Boolean = { true },
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = numeric,
                placeholder = placeholder?.let { { Text(it) } },
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid(text)) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wizard_cancel)) } },
    )
}
