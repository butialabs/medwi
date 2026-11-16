package br.com.butialabs.medwi.ui.settings

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.alarm.Notifications

/**
 * "Reliable reminders" checklist: shows which OS settings could keep dose reminders
 * from firing and opens the right screen to fix each. Re-checked on every resume, so
 * coming back from Settings updates the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReliabilityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var statuses by remember { mutableStateOf(ReminderReliability.statuses(context)) }
    fun refresh() { statuses = ReminderReliability.statuses(context) }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Denied for good -> the system won't ask again; send the user to Settings.
        if (!granted) openFix(context, ReliabilityCheck.NOTIFICATIONS)
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reliability_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.wizard_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val pending = statuses.count { !it.ok }
            Text(
                if (pending == 0) stringResource(R.string.reliability_all_ok)
                else stringResource(R.string.reliability_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            statuses.forEach { status ->
                CheckCard(
                    status = status,
                    onFix = {
                        val needsRuntimePermission = status.check == ReliabilityCheck.NOTIFICATIONS &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                        if (needsRuntimePermission) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            openFix(context, status.check)
                        }
                    },
                    onConfirm = { done ->
                        ReminderReliability.setAutostartConfirmed(context, done)
                        refresh()
                    },
                )
            }

            OutlinedButton(
                onClick = { Notifications.showTest(context) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(R.string.reliability_test)) }
            Text(
                stringResource(R.string.reliability_test_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun openFix(context: Context, check: ReliabilityCheck) {
    ReminderReliability.fixIntent(context, check)?.let { ReminderReliability.launch(context, it) }
}

@Composable
private fun CheckCard(status: CheckStatus, onFix: () -> Unit, onConfirm: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when {
        status.ok -> Icons.Filled.CheckCircle to scheme.primary
        !status.verifiable -> Icons.Outlined.HelpOutline to scheme.tertiary
        else -> Icons.Filled.ErrorOutline to scheme.error
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(status.check.titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        when {
                            status.ok -> R.string.reliability_ok
                            !status.verifiable -> R.string.reliability_check_manually
                            else -> R.string.reliability_needs_fix
                        }
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                )
            }
            Text(
                stringResource(status.check.bodyRes),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (status.check == ReliabilityCheck.AUTOSTART) {
                Text(
                    stringResource(ReminderReliability.autostartTipRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (status.check == ReliabilityCheck.AUTOSTART) {
                    TextButton(onClick = { onConfirm(!status.ok) }) {
                        Text(stringResource(if (status.ok) R.string.reliability_undo_done else R.string.reliability_mark_done))
                    }
                }
                if (!status.ok || !status.verifiable) {
                    Button(onClick = onFix) { Text(stringResource(R.string.reliability_fix)) }
                } else {
                    TextButton(onClick = onFix) { Text(stringResource(R.string.reliability_open)) }
                }
            }
        }
    }
}
