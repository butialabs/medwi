package br.com.butialabs.medwi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.draw.alpha
import br.com.butialabs.medwi.data.db.MedListItem
import br.com.butialabs.medwi.data.db.TreatmentState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.butialabs.medwi.ui.settings.ReminderReliability
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.db.AsNeededMed
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.ui.addmed.formLabel
import br.com.butialabs.medwi.ui.pill.PillImage
import br.com.butialabs.medwi.ui.theme.MedwiTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun TodayScreen(vm: MedViewModel, onOpenReliability: () -> Unit = {}) {
    val loaded by vm.doses.collectAsStateWithLifecycle()
    val doses = loaded ?: return // still loading: draw nothing rather than "no doses"
    val asNeeded by vm.asNeeded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasIssues by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        hasIssues = ReminderReliability.hasCriticalIssues(context)
        vm.refreshToday()
        onPauseOrDispose { }
    }

    var sheetDose by remember { mutableStateOf<DoseView?>(null) }
    var takeAllSlot by remember { mutableStateOf<Pair<Long, List<Long>>?>(null) }
    sheetDose?.let { d -> DoseActionsSheet(d, vm, onDismiss = { sheetDose = null }) }
    takeAllSlot?.let { (at, ids) ->
        val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().format(hm)
        AlertDialog(
            onDismissRequest = { takeAllSlot = null },
            title = { Text(stringResource(R.string.take_all_confirm_title, ids.size, time)) },
            confirmButton = {
                TextButton(onClick = { vm.takeAll(ids); takeAllSlot = null }) {
                    Text(stringResource(R.string.action_take_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { takeAllSlot = null }) { Text(stringResource(R.string.wizard_cancel)) }
            },
        )
    }

    if (doses.isEmpty() && asNeeded.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (hasIssues) ReliabilityBanner(onOpenReliability, Modifier.padding(16.dp))
            EmptyState(Icons.Outlined.EventAvailable, stringResource(R.string.empty_today))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (hasIssues) {
            item(key = "reliability") { ReliabilityBanner(onOpenReliability) }
        }
        doses.groupBy { it.scheduledAtMillis }.forEach { (at, slot) ->
            val awaiting = slot.filter { it.uiState() !in ANSWERED }
            item(key = "slot-$at") {
                SlotHeader(
                    time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime().format(hm),
                    awaiting = awaiting.size,
                    onTakeAll = { takeAllSlot = at to awaiting.map { it.itemId } },
                )
            }
            items(slot, key = { it.itemId }) { dose ->
                DoseRow(
                    dose = dose,
                    onTake = { vm.take(dose.itemId) },
                    onSnooze = { vm.snooze(dose.itemId) },
                    onSkip = { vm.skip(dose.itemId) },
                    onMore = { sheetDose = dose },
                )
            }
        }
        if (asNeeded.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.as_needed_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(asNeeded, key = { "an-${it.groupId}" }) { med ->
                AsNeededRow(med, onLog = { vm.logExtraDose(med.groupId) })
            }
        }
    }
}

private val ANSWERED = setOf(DoseUiState.TAKEN, DoseUiState.SKIPPED)

/** Time slot header on Today, with "Take all" when several doses are still open. */
@Composable
private fun SlotHeader(time: String, awaiting: Int, onTakeAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (awaiting >= 2) {
            TextButton(onClick = onTakeAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.take_all_count, awaiting))
            }
        }
    }
}

/** Warns that reminders may not fire (battery optimization, permissions...). */
@Composable
private fun ReliabilityBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsOff, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.reliability_banner_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.reliability_banner_body), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.reliability_review), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AsNeededRow(med: AsNeededMed, onLog: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MedAvatar(med.iconKey, med.colorHex, color2Hex = med.color2Hex)
            Spacer(Modifier.width(12.dp))
            Text(
                medTitle(med.name, med.strengthValue, med.strengthUnit),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onLog) { Text(stringResource(R.string.action_log_dose)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DoseRow(
    dose: DoseView,
    onTake: () -> Unit,
    onSnooze: () -> Unit,
    onSkip: () -> Unit,
    onMore: (() -> Unit)? = null,
) {
    val done = dose.status == "TAKEN" || dose.status == "SKIPPED"
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp)) {
            MedAvatar(dose.iconKey, dose.colorHex, color2Hex = dose.color2Hex)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val strength = strengthText(dose.strengthValue, dose.strengthUnit)
                Text(
                    buildString {
                        append(dose.medicineName)
                        if (strength != null) append(" · $strength")
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doseText(dose.dosageValue, dose.dosageUnit),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    DoseStatusChip(dose.uiState())
                }
                dose.note?.let {
                    Text(
                        stringResource(R.string.note_label, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!done) {
                    // FlowRow: with large fonts the buttons wrap instead of being squeezed
                    // to zero width (which made a tall, empty gap inside the card).
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = onTake) { Text(stringResource(R.string.action_take)) }
                        TextButton(onClick = onSnooze) { Text(stringResource(R.string.action_snooze)) }
                        TextButton(onClick = onSkip) { Text(stringResource(R.string.action_skip)) }
                    }
                }
            }
            if (onMore != null) {
                IconButton(onClick = onMore) {
                    Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                }
            }
        }
    }
}

/** Circular colored avatar with the medication's preset icon. */
@Composable
internal fun MedAvatar(iconKey: String?, colorHex: String?, size: Int = 44, color2Hex: String? = null) {
    Box(
        Modifier.size(size.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PillImage(iconKey, colorHex, color2Hex, Modifier.size((size * 0.82f).dp))
    }
}

@Composable
fun MedListScreen(vm: MedViewModel, onOpen: (Long) -> Unit = {}) {
    val items by vm.medList.collectAsStateWithLifecycle()
    var showPaused by remember { mutableStateOf(true) }
    var showEnded by remember { mutableStateOf(false) }

    if (items.isEmpty()) {
        EmptyState(Icons.Outlined.Medication, stringResource(R.string.empty_meds))
        return
    }
    val byState = items.groupBy { it.state }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        byState[TreatmentState.ACTIVE]?.let { active ->
            if (byState.size > 1) item { SectionHeader(stringResource(R.string.meds_active), active.size) }
            items(active, key = { it.medicine.id }) { MedListRow(it, onOpen) }
        }
        byState[TreatmentState.PAUSED]?.let { paused ->
            item { SectionHeader(stringResource(R.string.meds_paused), paused.size, showPaused) { showPaused = !showPaused } }
            if (showPaused) items(paused, key = { it.medicine.id }) { MedListRow(it, onOpen) }
        }
        byState[TreatmentState.ENDED]?.let { ended ->
            item { SectionHeader(stringResource(R.string.meds_ended), ended.size, showEnded) { showEnded = !showEnded } }
            if (showEnded) items(ended, key = { it.medicine.id }) { MedListRow(it, onOpen) }
        }
        item { Spacer(Modifier.height(88.dp)) } // room for the FAB
    }
}

@Composable
private fun SectionHeader(title: String, count: Int, expanded: Boolean? = null, onToggle: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (expanded != null) {
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun MedListRow(item: MedListItem, onOpen: (Long) -> Unit) {
    val med = item.medicine
    val dim = item.state != TreatmentState.ACTIVE
    Card(onClick = { onOpen(med.id) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alpha(if (dim) 0.5f else 1f)) { MedAvatar(med.iconKey, med.color, color2Hex = med.color2) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    medTitle(med.name, med.strengthValue, med.strengthUnit),
                    style = MaterialTheme.typography.titleMedium,
                )
                val detail = formLabel(med.form)
                if (detail.isNotBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
                val stock = med.stockCount
                if (stock != null && item.state == TreatmentState.ACTIVE) {
                    val low = med.refillThreshold?.let { stock <= it } == true
                    Text(
                        stringResource(R.string.stock_left, stock),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (low) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(icon: ImageVector, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- Previews ----

private fun sampleDose(status: String) = DoseView(
    itemId = 1, groupId = 1, scheduledAtMillis = 1_700_000_000_000L,
    dosageValue = 1f, dosageUnit = "tablet", status = status,
    medicineName = "Aspirin", form = "tablet", strengthValue = "100", strengthUnit = "mg",
    iconKey = "round", colorHex = "#FAFAF7",
)

@Preview(name = "Dose · pending")
@Composable
private fun DoseRowPendingPreview() {
    MedwiTheme { DoseRow(sampleDose("PENDING"), {}, {}, {}) }
}

@Preview(name = "Dose · taken")
@Composable
private fun DoseRowTakenPreview() {
    MedwiTheme { DoseRow(sampleDose("TAKEN"), {}, {}, {}) }
}

@Preview(name = "Empty state")
@Composable
private fun EmptyStatePreview() {
    MedwiTheme { EmptyState(Icons.Outlined.Medication, "No medications yet. Tap + to add one.") }
}
