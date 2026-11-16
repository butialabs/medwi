package br.com.butialabs.medwi.ui.addmed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.com.butialabs.medwi.R

/** Localized label for a stored form token (e.g. "tablet" -> "Comprimido"). */
@Composable
fun formLabel(token: String?): String {
    val form = MedForm.fromToken(token)
    return if (form != null) stringResource(form.labelRes) else (token ?: "")
}

/**
 * Common chrome for a wizard step: top bar with back, progress bar, scrollable
 * body, and a bottom primary button (Next / Save).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepScaffold(
    title: String,
    step: Int,
    totalSteps: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
    @androidx.annotation.StringRes nextLabelRes: Int = R.string.wizard_next,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.wizard_back))
                        }
                    },
                )
                LinearProgressIndicator(
                    progress = { (step + 1f) / totalSteps },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        bottomBar = {
            Button(
                onClick = onNext,
                enabled = nextEnabled,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(nextLabelRes)) }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

/** A question headline shown above a step's options. */
@Composable
fun StepHeadline(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/** A selectable list row with optional trailing check or chevron (>=56dp tall). */
@Composable
fun OptionRow(
    text: String,
    selected: Boolean = false,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        when {
            selected -> Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
            showChevron -> Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

/** A labeled -/+ stepper for a small integer value. */
@Composable
fun NumberStepper(value: Int, onChange: (Int) -> Unit, min: Int = 1, max: Int = 99) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedIconButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }, enabled = value > min) {
            Icon(Icons.Filled.Remove, stringResource(R.string.wizard_decrease))
        }
        Text("$value", style = MaterialTheme.typography.headlineMedium)
        OutlinedIconButton(onClick = { onChange((value + 1).coerceAtMost(max)) }, enabled = value < max) {
            Icon(Icons.Filled.Add, stringResource(R.string.wizard_increase))
        }
    }
}

/** A circular color swatch for the icon/color picker. */
@Composable
fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Gray)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .padding(5.dp)
            .background(color, CircleShape),
    )
}

/** Centered helper text block. */
@Composable
fun StepHint(text: String) {
    Spacer(Modifier.size(4.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}
