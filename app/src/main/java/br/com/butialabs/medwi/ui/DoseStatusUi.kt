package br.com.butialabs.medwi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.db.DoseView

/** What a dose looks like to the user (an overdue PENDING dose reads as "missed"). */
enum class DoseUiState { TAKEN, SKIPPED, SNOOZED, PENDING, MISSED }

fun DoseView.uiState(nowMillis: Long = System.currentTimeMillis()): DoseUiState = when (status) {
    "TAKEN" -> DoseUiState.TAKEN
    "SKIPPED" -> DoseUiState.SKIPPED
    "SNOOZED" -> DoseUiState.SNOOZED
    "MISSED" -> DoseUiState.MISSED
    else -> if (scheduledAtMillis < nowMillis - MISSED_AFTER_MILLIS) DoseUiState.MISSED else DoseUiState.PENDING
}

/** A pending dose counts as missed once it's this late without an answer. */
private const val MISSED_AFTER_MILLIS = 60 * 60_000L

/** Small rounded status label, colored like the Progress calendar. */
@Composable
fun DoseStatusChip(state: DoseUiState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg, label) = when (state) {
        DoseUiState.TAKEN -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, R.string.status_taken)
        DoseUiState.SKIPPED -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, R.string.status_skipped)
        DoseUiState.SNOOZED -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, R.string.status_snoozed)
        DoseUiState.PENDING -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, R.string.status_pending)
        DoseUiState.MISSED -> Triple(scheme.errorContainer, scheme.onErrorContainer, R.string.status_missed)
    }
    Text(
        stringResource(label),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

