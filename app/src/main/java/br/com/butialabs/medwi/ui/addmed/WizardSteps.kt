package br.com.butialabs.medwi.ui.addmed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.ui.doseText
import br.com.butialabs.medwi.ui.medTitle
import br.com.butialabs.medwi.ui.pill.PillImage
import br.com.butialabs.medwi.ui.sound.SoundPickerSheet
import br.com.butialabs.medwi.ui.sound.SoundRow
import br.com.butialabs.medwi.ui.sound.medicineSoundLabel

private val cyclePresets = listOf(21 to 7, 24 to 4, 28 to 0, 3 to 1, 84 to 7, 14 to 14, 63 to 7, 26 to 2)

// ---------- Step 1: name + autocomplete ----------

@Composable
fun NameContent(
    draft: MedDraft,
    suggestions: List<String>,
    onQuery: (String) -> Unit,
    onChange: (MedDraft) -> Unit,
) {
    StepHeadline(stringResource(R.string.q_name))
    OutlinedTextField(
        value = draft.name,
        onValueChange = { onChange(draft.copy(name = it)); onQuery(it) },
        label = { Text(stringResource(R.string.label_med_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (draft.name.isNotBlank() && suggestions.isNotEmpty() && suggestions.firstOrNull() != draft.name) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                suggestions.take(6).forEach { s ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable { onChange(draft.copy(name = s)); onQuery("") }
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(s) }
                }
            }
        }
    }
}

// ---------- Step 2: form + strength + dose quantity ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FormContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    StepHeadline(stringResource(R.string.q_form))
    MedForm.entries.forEach { f ->
        OptionRow(
            text = stringResource(f.labelRes),
            selected = draft.form == f,
            onClick = { onChange(draft.withForm(f)) },
        )
    }
    if (draft.form == MedForm.OTHER) {
        OutlinedTextField(
            value = draft.formOther,
            onValueChange = { onChange(draft.copy(formOther = it)) },
            label = { Text(stringResource(R.string.label_form_other)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

// ---------- Step 3: frequency type ----------

@Composable
fun FrequencyContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    StepHeadline(stringResource(R.string.q_frequency))
    FrequencyChoice.entries.forEach { c ->
        OptionRow(
            text = stringResource(c.labelRes),
            selected = draft.frequency == c,
            onClick = { onChange(draft.copy(frequency = c)) },
        )
    }
}

// ---------- Step 4: frequency detail (conditional) ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    when (draft.frequency) {
        // Doses per day for these is chosen in the times step (interval organizer).
        FrequencyChoice.EVERY_DAY, FrequencyChoice.ALTERNATE_DAYS -> Unit
        FrequencyChoice.DAYS_OF_WEEK -> {
            StepHeadline(stringResource(R.string.q_days_of_week))
            val labels = listOf(
                1 to R.string.dow_mon, 2 to R.string.dow_tue, 3 to R.string.dow_wed,
                4 to R.string.dow_thu, 5 to R.string.dow_fri, 6 to R.string.dow_sat, 7 to R.string.dow_sun,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels.forEach { (iso, res) ->
                    FilterChip(
                        selected = iso in draft.daysOfWeek,
                        onClick = {
                            val next = if (iso in draft.daysOfWeek) draft.daysOfWeek - iso else draft.daysOfWeek + iso
                            onChange(draft.copy(daysOfWeek = next))
                        },
                        label = { Text(stringResource(res)) },
                    )
                }
            }
        }
        FrequencyChoice.CYCLE -> {
            StepHeadline(stringResource(R.string.q_cycle))
            cyclePresets.forEach { (take, brk) ->
                val label = if (brk == 0) "$take" else "$take+$brk"
                OptionRow(
                    text = label,
                    selected = draft.cyclePillDays == take && draft.cycleBreakDays == brk,
                    onClick = { onChange(draft.copy(cyclePillDays = take, cycleBreakDays = brk)) },
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.cyclePillDays.toString(),
                    onValueChange = { onChange(draft.copy(cyclePillDays = it.toIntOrNull()?.coerceAtLeast(1) ?: 1)) },
                    label = { Text(stringResource(R.string.label_take_days)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.cycleBreakDays.toString(),
                    onValueChange = { onChange(draft.copy(cycleBreakDays = it.toIntOrNull()?.coerceAtLeast(0) ?: 0)) },
                    label = { Text(stringResource(R.string.label_break_days)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        FrequencyChoice.EVERY_X_DAYS, FrequencyChoice.EVERY_X_WEEKS, FrequencyChoice.EVERY_X_MONTHS -> {
            val unitRes = when (draft.frequency) {
                FrequencyChoice.EVERY_X_WEEKS -> R.string.unit_weeks
                FrequencyChoice.EVERY_X_MONTHS -> R.string.unit_months
                else -> R.string.unit_days
            }
            StepHeadline(stringResource(R.string.q_interval))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.every_label))
                NumberStepper(draft.everyX, { onChange(draft.copy(everyX = it)) }, min = 1, max = 99)
                Text(stringResource(unitRes))
            }
        }
        FrequencyChoice.AS_NEEDED -> Unit
    }
}

// ---------- Step 5: times (interval organizer) ----------

/** Sentinel indices for the time picker: add a free time / change the first dose. */
private const val EDIT_ADD = -1
private const val EDIT_FIRST = -2

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimesContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) } // index into times, or EDIT_*

    StepHeadline(stringResource(R.string.q_times))

    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.auto_schedule), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (draft.autoSchedule) R.string.auto_schedule_on_hint else R.string.auto_schedule_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = draft.autoSchedule, onCheckedChange = { onChange(draft.withAutoSchedule(it)) })
    }

    if (draft.autoSchedule) {
        Text(
            stringResource(R.string.doses_per_day),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        NumberStepper(draft.timesPerDay, { onChange(draft.withDosesPerDay(it)) }, min = 1, max = MAX_DOSES_PER_DAY)

        Text(
            stringResource(R.string.every_x_hours),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IntervalHourChoices.forEach { h ->
                FilterChip(
                    selected = draft.intervalMinutes == h * 60,
                    onClick = { onChange(draft.withIntervalHours(h)) },
                    label = { Text(stringResource(R.string.interval_hours_chip, h)) },
                )
            }
        }
        StepHint(stringResource(R.string.interval_summary, intervalLabel(draft.intervalMinutes)))

        OutlinedButton(
            onClick = { editing = EDIT_FIRST },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) { Text(stringResource(R.string.first_dose_at, draft.firstTime.toString())) }
    }

    Text(
        stringResource(R.string.dose_times),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp),
    )
    draft.times.forEachIndexed { i, t ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { editing = i }, modifier = Modifier.weight(1f)) {
                Text("${stringResource(R.string.dose_label)} ${i + 1}:  $t")
            }
            if (!draft.autoSchedule) {
                IconButton(
                    onClick = { onChange(draft.copy(times = draft.times.filterIndexed { j, _ -> j != i })) },
                    enabled = draft.times.size > 1,
                ) { Icon(Icons.Filled.Delete, stringResource(R.string.wizard_remove)) }
            }
        }
    }
    if (draft.autoSchedule) {
        StepHint(stringResource(R.string.auto_schedule_edit_hint))
    } else {
        OutlinedButton(onClick = { editing = EDIT_ADD }, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.add_time))
        }
    }

    editing?.let { idx ->
        val initial = when (idx) {
            EDIT_FIRST -> draft.firstTime
            EDIT_ADD -> TimeOfDay(8, 0)
            else -> draft.times.getOrNull(idx) ?: TimeOfDay(8, 0)
        }
        TimePickerDialog(
            initial = initial,
            onConfirm = { picked ->
                val next = when (idx) {
                    EDIT_FIRST -> draft.withFirstTime(picked)
                    EDIT_ADD -> draft.copy(times = (draft.times + picked).distinct().sortedBy { it.minuteOfDay() })
                    else -> draft.withEditedTime(draft.times[idx], picked)
                }
                onChange(next)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** "8 h" or "4 h 48 min". */
@Composable
private fun intervalLabel(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0) stringResource(R.string.interval_hours_chip, h)
    else stringResource(R.string.interval_hours_minutes, h, m)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: TimeOfDay, onConfirm: (TimeOfDay) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(TimeOfDay(state.hour, state.minute)) }) {
                Text(stringResource(R.string.wizard_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wizard_cancel)) } },
        text = { TimePicker(state = state) },
    )
}

// ---------- Step 6: review + "quase pronto" extras ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    StepHeadline(stringResource(R.string.q_review))

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { PillImage(draft.iconKey, draft.colorHex, draft.color2Hex, Modifier.size(40.dp)) }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        medTitle(draft.name.ifBlank { stringResource(R.string.app_name) }, draft.strengthValue, draft.strengthUnit),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${doseText(draft.dosageValue, draft.doseUnit)} · ${formLabel(draft.formToken())}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(stringResource(draft.frequency.labelRes), style = MaterialTheme.typography.bodySmall)
            if (draft.frequency.hasTimes) {
                Text(draft.times.joinToString("  ") { it.toString() }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    Text(
        stringResource(R.string.almost_done),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )

    // Duration
    OutlinedTextField(
        value = draft.durationDays?.toString() ?: "",
        onValueChange = { onChange(draft.copy(durationDays = it.toIntOrNull()?.takeIf { d -> d > 0 })) },
        label = { Text(stringResource(R.string.label_duration_days)) },
        placeholder = { Text(stringResource(R.string.duration_continuous)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // Instructions
    OutlinedTextField(
        value = draft.instructions,
        onValueChange = { onChange(draft.copy(instructions = it)) },
        label = { Text(stringResource(R.string.label_instructions)) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    // Reminder sound
    var pickSound by remember { mutableStateOf(false) }
    SoundRow(
        value = medicineSoundLabel(draft.reminderSound),
        onClick = { pickSound = true },
        modifier = Modifier.padding(top = 4.dp),
    )
    if (pickSound) {
        SoundPickerSheet(
            selected = draft.reminderSound,
            perMedicine = true,
            onSelect = { onChange(draft.copy(reminderSound = it)) },
            onDismiss = { pickSound = false },
        )
    }
    // Refill
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.stockCount,
            onValueChange = { onChange(draft.copy(stockCount = it)) },
            label = { Text(stringResource(R.string.label_stock)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.refillThreshold,
            onValueChange = { onChange(draft.copy(refillThreshold = it)) },
            label = { Text(stringResource(R.string.label_refill_at)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    if (draft.stockCount.isNotBlank()) {
        var pickRefillTime by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft.unitsPerPackage,
                onValueChange = { onChange(draft.copy(unitsPerPackage = it.filter(Char::isDigit).take(4))) },
                label = { Text(stringResource(R.string.label_units_per_box)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { pickRefillTime = true }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.label_refill_time, draft.refillTime.toString()))
            }
        }
        if (pickRefillTime) {
            TimePickerDialog(
                initial = draft.refillTime,
                onConfirm = { onChange(draft.copy(refillTime = it)); pickRefillTime = false },
                onDismiss = { pickRefillTime = false },
            )
        }
    }
    if (draft.frequency.hasTimes) RampSection(draft, onChange)
}

/** "The dose changes over time": taper down or step up by X every N days until a final dose. */
@Composable
private fun RampSection(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ramp_switch), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.ramp_switch_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = draft.rampEnabled, onCheckedChange = { onChange(draft.copy(rampEnabled = it)) })
    }
    if (!draft.rampEnabled) return

    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = draft.rampDown,
            onClick = { onChange(draft.copy(rampDown = true)) },
            label = { Text(stringResource(R.string.ramp_down)) },
        )
        FilterChip(
            selected = !draft.rampDown,
            onClick = { onChange(draft.copy(rampDown = false)) },
            label = { Text(stringResource(R.string.ramp_up)) },
        )
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.rampAmount,
            onValueChange = { onChange(draft.copy(rampAmount = it)) },
            label = { Text(stringResource(R.string.ramp_amount)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.rampEveryDays.toString(),
            onValueChange = { onChange(draft.copy(rampEveryDays = it.toIntOrNull()?.coerceIn(1, 365) ?: 1)) },
            label = { Text(stringResource(R.string.ramp_every_days)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.rampFinal,
            onValueChange = { onChange(draft.copy(rampFinal = it)) },
            label = { Text(stringResource(R.string.ramp_final)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
    val preview = draft.rampPreview()
    if (preview.isEmpty()) {
        StepHint(stringResource(R.string.ramp_invalid))
    } else {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM")
        val parts = mutableListOf<String>()
        for ((date, dose) in preview) {
            parts += if (dose <= 0f) stringResource(R.string.ramp_preview_stop, date.format(fmt))
            else "${doseText(dose, draft.doseUnit)} (${date.format(fmt)})"
        }
        StepHint(parts.joinToString("  →  "))
    }
}
