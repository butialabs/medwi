package br.com.butialabs.medwi.ui.addmed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.ui.doseText
import br.com.butialabs.medwi.ui.medTitle
import br.com.butialabs.medwi.ui.pill.PillColors
import br.com.butialabs.medwi.ui.pill.PillImage
import br.com.butialabs.medwi.ui.pill.PillShape
import br.com.butialabs.medwi.ui.pill.ShapeGroup

// ---------- Strength + dose ----------

/** "Euthyrox 125 mg", "1 comprimido": strength (optional) and the amount of one dose. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StrengthDoseContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    StepHeadline(stringResource(R.string.q_strength))

    Text(stringResource(R.string.strength_title), style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = draft.strengthValue,
        onValueChange = { onChange(draft.copy(strengthValue = it.take(12))) },
        label = { Text(stringResource(R.string.label_strength_value)) },
        suffix = { Text(draft.strengthUnit) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    val units = (StrengthUnits + draft.strengthUnit).filter { it.isNotBlank() }.distinct()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        units.forEach { u ->
            FilterChip(
                selected = draft.strengthUnit == u,
                onClick = { onChange(draft.copy(strengthUnit = u)) },
                label = { Text(u) },
            )
        }
    }
    StepHint(stringResource(R.string.strength_hint))

    Text(
        stringResource(R.string.dose_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 16.dp),
    )
    OutlinedTextField(
        value = draft.dosageQty,
        onValueChange = { onChange(draft.copy(dosageQty = it.take(6))) },
        label = { Text(stringResource(R.string.label_dose_qty)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    // Units for this form first, then the rest.
    val preferred = draft.form.defaultDoseUnit
    val doseUnits = listOf(preferred) + DoseUnit.entries.filter { it != preferred && it != DoseUnit.MG }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        doseUnits.forEach { u ->
            FilterChip(
                selected = draft.doseUnit == u.token,
                onClick = { onChange(draft.copy(doseUnit = u.token, doseUnitTouched = true)) },
                label = { Text(doseText(draft.dosageValue, u.token)) },
            )
        }
    }
    val title = medTitle(draft.name.trim(), draft.strengthValue, draft.strengthUnit)
    StepHint(stringResource(R.string.dose_preview, doseText(draft.dosageValue, draft.doseUnit), title))
}

// ---------- Appearance ----------

/** Shape + color(s) of the medicine, shown instead of a generic icon everywhere. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceContent(draft: MedDraft, onChange: (MedDraft) -> Unit) {
    val shape = PillShape.fromKey(draft.iconKey)
    var showAll by remember { mutableStateOf(false) }

    StepHeadline(stringResource(R.string.q_appearance))
    Box(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(128.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            PillImage(draft.iconKey, draft.colorHex, draft.color2Hex, Modifier.size(100.dp))
        }
    }
    StepHint(stringResource(R.string.appearance_hint))

    Text(stringResource(R.string.label_shape), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val group = draft.form.shapeGroup()
    val (main, more) = PillShape.entries.partition { it.group == group }
    ShapeGrid(main, draft) { onChange(draft.withShape(it)) }
    if (showAll || shape in more) {
        ShapeGrid(more, draft) { onChange(draft.withShape(it)) }
    } else {
        TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.more_shapes)) }
    }

    Text(stringResource(R.string.label_color), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    Swatches(selected = draft.colorHex) { onChange(draft.copy(colorHex = it, appearanceTouched = true)) }
    if (shape.twoTone) {
        Text(stringResource(R.string.label_color2), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Swatches(selected = draft.color2Hex) { onChange(draft.copy(color2Hex = it, appearanceTouched = true)) }
    }
}

private fun MedForm.shapeGroup(): ShapeGroup = when (this) {
    MedForm.SOLUTION, MedForm.DROPS -> ShapeGroup.LIQUID
    MedForm.INJECTION, MedForm.INHALER, MedForm.CREAM, MedForm.PATCH -> ShapeGroup.DEVICE
    else -> ShapeGroup.SOLID
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShapeGrid(shapes: List<PillShape>, draft: MedDraft, onPick: (PillShape) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        shapes.forEach { s ->
            val selected = draft.iconKey == s.key
            val label = stringResource(s.labelRes)
            Box(
                Modifier
                    .size(56.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(14.dp),
                    )
                    .border(
                        width = if (selected) 2.dp else 0.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable(role = Role.RadioButton) { onPick(s) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                // Untouched drafts preview each shape in its own default colors.
                val c1 = if (draft.appearanceTouched) draft.colorHex else s.defaultColor
                val c2 = if (draft.appearanceTouched) draft.color2Hex else s.defaultColor2
                PillImage(s.key, c1, c2, Modifier.size(44.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(selected: String, onPick: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        PillColors.all.forEach { sw ->
            val label = stringResource(sw.labelRes)
            Box(Modifier.semantics { contentDescription = label }) {
                ColorSwatch(hex = sw.hex, selected = selected.equals(sw.hex, ignoreCase = true)) { onPick(sw.hex) }
            }
        }
    }
}
