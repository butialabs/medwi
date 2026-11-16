package br.com.butialabs.medwi.ui

import androidx.hilt.navigation.compose.hiltViewModel
import br.com.butialabs.medwi.ui.sound.SoundPickerSheet
import br.com.butialabs.medwi.ui.sound.SoundRow
import br.com.butialabs.medwi.ui.sound.SoundViewModel
import br.com.butialabs.medwi.ui.sound.medicineSoundLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import br.com.butialabs.medwi.data.db.TreatmentState
import br.com.butialabs.medwi.ui.addmed.FrequencyChoice
import br.com.butialabs.medwi.ui.addmed.MedDraft
import br.com.butialabs.medwi.ui.addmed.formLabel
import br.com.butialabs.medwi.ui.addmed.qtyText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM")

private fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

private fun LocalDate.medium(): String = format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** One medication: schedule, stock/refill, and treatment actions (pause, end, delete...). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedDetailScreen(vm: MedViewModel, medicineId: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val detail by remember(medicineId) { vm.medDetail(medicineId) }.collectAsStateWithLifecycle(initialValue = null)
    val d = detail
    var confirm by remember { mutableStateOf<DetailConfirm?>(null) }
    var stockDialog by remember { mutableStateOf<StockDialog?>(null) }
    var pickSound by remember { mutableStateOf(false) }
    val soundVm: SoundViewModel = hiltViewModel()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.medicine?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.wizard_back))
                    }
                },
                actions = {
                    if (d != null) {
                        IconButton(onClick = { onEdit(medicineId) }) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.action_edit))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (d == null) return@Scaffold
        val med = d.medicine
        val group = d.group
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderCard(med, group, d.state)
            if (group != null) RampCard(med, group)
            StockCard(
                detail = d,
                onAddBox = { box -> vm.addStock(med.id, box) },
                onAdd = { stockDialog = StockDialog.ADD },
                onAdjust = { stockDialog = StockDialog.ADJUST },
            )
            Card(Modifier.fillMaxWidth()) {
                SoundRow(value = medicineSoundLabel(med.reminderSound), onClick = { pickSound = true })
            }
            ActionsCard(
                state = d.state,
                hasGroup = group != null,
                onLogNow = { group?.let { vm.logDoseNow(it.id) } },
                onPause = { confirm = DetailConfirm.PAUSE },
                onResume = { group?.let { vm.resume(it.id) } },
                onEnd = { confirm = DetailConfirm.END },
                onDelete = { confirm = DetailConfirm.DELETE },
            )
        }

        confirm?.let { kind ->
            val (title, body) = when (kind) {
                DetailConfirm.PAUSE -> stringResource(R.string.pause_title, med.name) to stringResource(R.string.pause_message)
                DetailConfirm.END -> stringResource(R.string.end_title) to stringResource(R.string.end_message)
                DetailConfirm.DELETE -> stringResource(R.string.delete_title) to
                    stringResource(R.string.delete_message, med.name) + "\n\n" + stringResource(R.string.delete_history_warning)
            }
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text(title) },
                text = { Text(body) },
                confirmButton = {
                    TextButton(onClick = {
                        when (kind) {
                            DetailConfirm.PAUSE -> group?.let { vm.pause(it.id) }
                            DetailConfirm.END -> group?.let { vm.end(it.id) }
                            DetailConfirm.DELETE -> { vm.deleteMedicine(med.id); onBack() }
                        }
                        confirm = null
                    }) {
                        Text(
                            stringResource(
                                when (kind) {
                                    DetailConfirm.PAUSE -> R.string.action_pause
                                    DetailConfirm.END -> R.string.action_end
                                    DetailConfirm.DELETE -> R.string.action_delete
                                }
                            )
                        )
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.wizard_cancel)) } },
            )
        }

        if (pickSound) {
            SoundPickerSheet(
                selected = med.reminderSound,
                perMedicine = true,
                onSelect = { soundVm.setMedicine(med.id, it) },
                onDismiss = { pickSound = false },
                vm = soundVm,
            )
        }

        stockDialog?.let { kind ->
            NumberDialog(
                title = stringResource(if (kind == StockDialog.ADD) R.string.stock_add_hint else R.string.stock_adjust_hint),
                initial = if (kind == StockDialog.ADJUST) med.stockCount?.toString() ?: "" else "",
                onConfirm = { n ->
                    if (kind == StockDialog.ADD) vm.addStock(med.id, n) else vm.setStock(med.id, n)
                    stockDialog = null
                },
                onDismiss = { stockDialog = null },
            )
        }
    }
}

private enum class DetailConfirm { PAUSE, END, DELETE }
private enum class StockDialog { ADD, ADJUST }

@Composable
private fun HeaderCard(med: MedicineEntity, group: ScheduleGroupEntity?, state: TreatmentState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MedAvatar(med.iconKey, med.color, size = 64, color2Hex = med.color2)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    medTitle(med.name, med.strengthValue, med.strengthUnit),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (group != null) {
                    Text(
                        doseText(group.dosageValue, group.dosageUnit),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        scheduleSummary(med, group),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                med.instructions?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                when (state) {
                    TreatmentState.PAUSED -> group?.pausedAtMillis?.let {
                        StateLabel(stringResource(R.string.state_paused_since, it.toLocalDate().medium()))
                    }
                    TreatmentState.ENDED -> group?.endedAtMillis?.let {
                        StateLabel(stringResource(R.string.state_ended_on, it.toLocalDate().medium()))
                    }
                    TreatmentState.ACTIVE -> Unit
                }
            }
        }
    }
}

@Composable
private fun StateLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** "Every day · 08:00, 20:00" from the stored schedule. */
@Composable
fun scheduleSummary(med: MedicineEntity, group: ScheduleGroupEntity): String {
    val draft = MedDraft.fromExisting(med, group)
    val freq = stringResource(draft.frequency.labelRes)
    if (draft.frequency == FrequencyChoice.AS_NEEDED) return freq
    val times = draft.times.sortedBy { it.minuteOfDay() }.joinToString(", ")
    return "$freq · $times"
}

/** Upcoming dose changes of a variable-dose schedule. */
@Composable
private fun RampCard(med: MedicineEntity, group: ScheduleGroupEntity) {
    val draft = MedDraft.fromExisting(med, group)
    val steps = draft.rampPreview(group.startDateEpochDay)
    if (steps.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.detail_dose_changes), style = MaterialTheme.typography.titleSmall)
            val today = LocalDate.now()
            steps.forEach { (date, dose) ->
                val past = date.isBefore(today)
                Text(
                    if (dose <= 0f) stringResource(R.string.detail_ramp_stop, date.format(shortDate))
                    else stringResource(R.string.detail_ramp_until, doseText(dose, group.dosageUnit), date.format(shortDate)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StockCard(detail: MedDetail, onAddBox: (Int) -> Unit, onAdd: () -> Unit, onAdjust: () -> Unit) {
    val med = detail.medicine
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.stock_title), style = MaterialTheme.typography.titleSmall)
            val stock = med.stockCount
            if (stock == null) {
                Text(
                    stringResource(R.string.stock_not_tracked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val low = med.refillThreshold?.let { stock <= it } == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.stock_left, stock),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    if (low) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.stock_low),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (detail.state == TreatmentState.ACTIVE && detail.group?.scheduleType != "AS_NEEDED") {
                    Text(
                        detail.runsOutOn?.let { stringResource(R.string.stock_lasts_until, it.format(shortDate)) }
                            ?: stringResource(R.string.stock_lasts_long),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val box = med.unitsPerPackage
                if (box != null && box > 0) {
                    Button(onClick = { onAddBox(box) }) { Text(stringResource(R.string.stock_add_box, box)) }
                } else {
                    Button(onClick = onAdd) { Text(stringResource(R.string.stock_add)) }
                }
                OutlinedButton(onClick = onAdjust) { Text(stringResource(R.string.stock_adjust)) }
            }
            if (detail.refills.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.refill_history), style = MaterialTheme.typography.labelLarge)
                detail.refills.take(6).forEach { r ->
                    val amount = if (r.amount >= 0) "+${r.amount}" else "${r.amount}"
                    Text(
                        stringResource(R.string.refill_entry, r.atMillis.toLocalDate().medium(), amount, r.resultingStock),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionsCard(
    state: TreatmentState,
    hasGroup: Boolean,
    onLogNow: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            if (hasGroup && state == TreatmentState.ACTIVE) {
                ActionRow(Icons.Outlined.TaskAlt, stringResource(R.string.action_log_now), onLogNow)
                ActionRow(Icons.Outlined.Pause, stringResource(R.string.action_pause), onPause)
            }
            if (hasGroup && state != TreatmentState.ACTIVE) {
                ActionRow(Icons.Outlined.PlayArrow, stringResource(R.string.action_resume), onResume)
            }
            if (hasGroup && state != TreatmentState.ENDED) {
                ActionRow(Icons.Outlined.StopCircle, stringResource(R.string.action_end), onEnd)
            }
            ActionRow(Icons.Outlined.Delete, stringResource(R.string.action_delete), onDelete, danger = true)
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = color),
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

/** Small dialog asking for a whole number (stock). */
@Composable
fun NumberDialog(title: String, initial: String, onConfirm: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val value = text.trim().toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onConfirm) }, enabled = value != null) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wizard_cancel)) } },
    )
}
