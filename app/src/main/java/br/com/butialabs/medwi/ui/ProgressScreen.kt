package br.com.butialabs.medwi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.core.adherence.AdherenceStats
import br.com.butialabs.medwi.core.adherence.DayAdherence
import br.com.butialabs.medwi.core.adherence.DayState
import br.com.butialabs.medwi.core.model.DoseStatus
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.ui.addmed.formLabel
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun ProgressScreen(vm: MedViewModel) {
    val stats by vm.monthStats.collectAsStateWithLifecycle()
    val selectedDay by vm.selectedProgressDay.collectAsStateWithLifecycle()
    val dayDoses by vm.progressDayDoses.collectAsStateWithLifecycle()
    var sheetDose by remember { mutableStateOf<DoseView?>(null) }
    sheetDose?.let { d -> DoseActionsSheet(d, vm, onDismiss = { sheetDose = null }) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AdherenceHeader(stats)
        MonthCalendar(
            stats = stats,
            selected = selectedDay,
            onSelect = vm::selectProgressDay,
            onPrev = vm::showPreviousMonth,
            onNext = vm::showNextMonth,
        )
        Legend()
        DayDetail(
            day = selectedDay,
            doses = dayDoses,
            onSetStatus = { dose, status -> vm.setDoseStatus(dose, status) },
            onMore = { sheetDose = it },
        )
    }
}

private fun monthLabel(month: YearMonth, locale: Locale): String =
    month.month.getDisplayName(TextStyle.FULL, locale)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() } +
        " ${month.year}"

@Composable
private fun AdherenceHeader(stats: AdherenceStats) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "${stats.percent}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.progress_adherence),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${stats.streak}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.progress_streak_days),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    stats: AdherenceStats,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val locale = Locale.getDefault()
    val label = monthLabel(stats.month, locale)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onPrev) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.progress_prev_month))
                }
                Text(label, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.progress_next_month))
                }
            }

            // Weekday headers, Sunday-first.
            val weekdays = (0..6).map { i ->
                // DayOfWeek: 1=Mon..7=Sun. Sunday-first index 0 -> SUNDAY(7).
                val dow = if (i == 0) java.time.DayOfWeek.SUNDAY else java.time.DayOfWeek.of(i)
                dow.getDisplayName(TextStyle.NARROW, locale)
            }
            Row(Modifier.fillMaxWidth()) {
                weekdays.forEach { w ->
                    Text(
                        w,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Leading blanks: Sunday-first, so Sun->0, Mon->1, ... Sat->6.
            val firstDow = stats.month.atDay(1).dayOfWeek.value % 7
            val cells: List<DayAdherence?> =
                List(firstDow) { null } + stats.days
            val today = LocalDate.now()
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    for (i in 0 until 7) {
                        val day = week.getOrNull(i)
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            if (day != null) {
                                DayCell(
                                    day,
                                    isToday = day.date == today,
                                    isSelected = day.date == selected,
                                    onClick = { onSelect(day.date) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: DayAdherence, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val bg = day.state.color()
    val ring = when {
        isSelected -> MaterialTheme.colorScheme.onSurface
        isToday -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .border(width = if (isSelected) 2.5.dp else 2.dp, color = ring, shape = CircleShape)
            .padding(4.dp)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${day.date.dayOfMonth}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.Bold else null,
            color = day.state.onColor(),
        )
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        LegendItem(DayState.COMPLETE, R.string.legend_complete)
        LegendItem(DayState.PARTIAL, R.string.legend_partial)
        LegendItem(DayState.MISSED, R.string.legend_missed)
    }
}

@Composable
private fun LegendItem(state: DayState, labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(state.color(), CircleShape))
        Spacer(Modifier.size(4.dp))
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
    }
}

/** The doses of the selected day, with Taken / Skipped / Undo for today and past days. */
@Composable
private fun DayDetail(
    day: LocalDate,
    doses: List<DoseView>,
    onSetStatus: (DoseView, DoseStatus) -> Unit,
    onMore: (DoseView) -> Unit,
) {
    val locale = Locale.getDefault()
    val title = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    val editable = !day.isAfter(LocalDate.now())

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (doses.isEmpty()) {
                Text(
                    stringResource(R.string.progress_no_doses_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            doses.forEachIndexed { i, dose ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                DoseHistoryRow(dose, editable, onSetStatus, onMore)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoseHistoryRow(
    dose: DoseView,
    editable: Boolean,
    onSetStatus: (DoseView, DoseStatus) -> Unit,
    onMore: (DoseView) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(dose.scheduledAtMillis).atZone(zone).toLocalTime().format(hm)
    val state = dose.uiState()
    val compact = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            MedAvatar(dose.iconKey, dose.colorHex, size = 36, color2Hex = dose.color2Hex)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$time · ${medTitle(dose.medicineName, dose.strengthValue, dose.strengthUnit)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                val amount = doseText(dose.dosageValue, dose.dosageUnit)
                val taken = dose.actualAtMillis
                val takenAt = if (state == DoseUiState.TAKEN && taken != null) {
                    stringResource(
                        R.string.taken_at,
                        Instant.ofEpochMilli(taken).atZone(zone).toLocalTime().format(hm),
                    )
                } else null
                Text(
                    listOfNotNull(amount, takenAt).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                )
                dose.note?.let {
                    Text(
                        stringResource(R.string.note_label, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (editable) {
                IconButton(onClick = { onMore(dose) }) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                }
            }
        }
        // Status chip + actions on one line under the text (aligned with the title),
        // so the text column keeps the full width instead of sharing it with the chip.
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DoseStatusChip(state)
            if (editable) {
                // FlowRow: with large fonts the buttons wrap instead of being squeezed.
                FlowRow(
                    Modifier.weight(1f).padding(start = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (state) {
                        DoseUiState.TAKEN, DoseUiState.SKIPPED -> TextButton(
                            onClick = { onSetStatus(dose, DoseStatus.PENDING) },
                            contentPadding = compact,
                        ) { Text(stringResource(R.string.action_undo)) }
                        else -> {
                            TextButton(
                                onClick = { onSetStatus(dose, DoseStatus.SKIPPED) },
                                contentPadding = compact,
                            ) { Text(stringResource(R.string.action_mark_skipped)) }
                            OutlinedButton(
                                onClick = { onSetStatus(dose, DoseStatus.TAKEN) },
                                contentPadding = compact,
                            ) { Text(stringResource(R.string.action_mark_taken)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayState.color(): Color = when (this) {
    DayState.COMPLETE -> MaterialTheme.colorScheme.primary
    DayState.PARTIAL -> MaterialTheme.colorScheme.tertiary
    DayState.MISSED -> MaterialTheme.colorScheme.error
    DayState.NONE -> MaterialTheme.colorScheme.surfaceVariant
    DayState.FUTURE -> Color.Transparent
}

@Composable
private fun DayState.onColor(): Color = when (this) {
    DayState.COMPLETE -> MaterialTheme.colorScheme.onPrimary
    DayState.PARTIAL -> MaterialTheme.colorScheme.onTertiary
    DayState.MISSED -> MaterialTheme.colorScheme.onError
    DayState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    DayState.FUTURE -> MaterialTheme.colorScheme.onSurfaceVariant
}
