package br.com.butialabs.medwi.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import br.com.butialabs.medwi.data.backup.AutoBackup
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import br.com.butialabs.medwi.BuildConfig
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.data.backup.BackupManager
import br.com.butialabs.medwi.data.backup.MedwiBackup
import br.com.butialabs.medwi.data.settings.AppSettings
import br.com.butialabs.medwi.alarm.ReminderSounds
import br.com.butialabs.medwi.ui.lock.AppLock
import br.com.butialabs.medwi.ui.sound.SoundPickerSheet
import br.com.butialabs.medwi.ui.sound.SoundRow
import br.com.butialabs.medwi.ui.sound.SoundViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val backup: BackupManager) : ViewModel()

/** Settings hub: reliable reminders, app lock, backup/restore, about. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenReliability: () -> Unit,
    onLockEnabled: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lockOn by remember { mutableStateOf(AppSettings.isLockEnabled(context)) }
    var pendingRestore by remember { mutableStateOf<MedwiBackup?>(null) }
    var generalSound by remember { mutableStateOf(AppSettings.reminderSound(context)) }
    var pickSound by remember { mutableStateOf(false) }
    val soundVm: SoundViewModel = hiltViewModel()

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun failed(e: Throwable) = toast(context.getString(R.string.backup_failed, e.message ?: e.javaClass.simpleName))

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use {
                        vm.backup.export(it, appVersion = BuildConfig.VERSION_NAME)
                    }
                }
            }.onSuccess { toast(context.getString(R.string.backup_exported)) }.onFailure(::failed)
        }
    }
    var autoStatus by remember { mutableStateOf(AutoBackup.status(context)) }
    LifecycleResumeEffect(Unit) {
        autoStatus = AutoBackup.status(context)
        onPauseOrDispose { }
    }
    // Refresh the "last backup" line when an on-demand backup finishes.
    val nowRuns by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(AutoBackup.NOW_WORK) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val autoRunning = nowRuns.any { !it.state.isFinished }
    LaunchedEffect(nowRuns.map { it.state }) { autoStatus = AutoBackup.status(context) }

    val autoFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            AutoBackup.enable(context, uri)
            autoStatus = AutoBackup.status(context)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { vm.backup.read(it) }
                }
            }.onSuccess { pendingRestore = it }.onFailure(::failed)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.wizard_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.reliability_title)) },
                supportingContent = { Text(stringResource(R.string.settings_reliability_sub)) },
                leadingContent = { Icon(Icons.Outlined.NotificationsActive, null) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable(onClick = onOpenReliability),
            )
            HorizontalDivider()

            val generalName = remember(generalSound) { ReminderSounds.displayName(context, generalSound) }
            SoundRow(
                value = generalName,
                supporting = stringResource(R.string.sound_settings_sub, generalName),
                onClick = { pickSound = true },
            )
            HorizontalDivider()

            val lockAvailable = remember { AppLock.isAvailable(context) }
            ListItem(
                headlineContent = { Text(stringResource(R.string.lock_title)) },
                supportingContent = {
                    Text(stringResource(if (lockAvailable || lockOn) R.string.lock_sub else R.string.lock_unavailable))
                },
                leadingContent = { Icon(Icons.Outlined.Lock, null) },
                trailingContent = {
                    Switch(
                        checked = lockOn,
                        enabled = lockAvailable || lockOn,
                        onCheckedChange = { on ->
                            if (on) {
                                val activity = context as? FragmentActivity ?: return@Switch
                                AppLock.confirmThenEnable(activity) {
                                    lockOn = true
                                    onLockEnabled()
                                }
                            } else {
                                AppSettings.setLockEnabled(context, false)
                                lockOn = false
                            }
                        },
                    )
                },
            )
            HorizontalDivider()

            Text(
                stringResource(R.string.backup_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
            )
            AutoBackupItem(
                status = autoStatus,
                running = autoRunning,
                onChooseFile = { autoFileLauncher.launch(AutoBackup.FILE_NAME) },
                onBackupNow = { AutoBackup.runNow(context) },
                onTurnOff = { AutoBackup.disable(context); autoStatus = AutoBackup.status(context) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_export)) },
                supportingContent = { Text(stringResource(R.string.backup_export_sub)) },
                leadingContent = { Icon(Icons.Outlined.CloudUpload, null) },
                modifier = Modifier.clickable {
                    exportLauncher.launch("medwi-backup-${LocalDate.now()}.json")
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.backup_import)) },
                supportingContent = { Text(stringResource(R.string.backup_import_sub)) },
                leadingContent = { Icon(Icons.Outlined.CloudDownload, null) },
                modifier = Modifier.clickable {
                    importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                },
            )
            Text(
                stringResource(R.string.backup_auto_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)) },
                leadingContent = { Icon(Icons.Outlined.Info, null) },
            )
        }
    }

    if (pickSound) {
        SoundPickerSheet(
            selected = generalSound,
            perMedicine = false,
            onSelect = { key ->
                generalSound = key ?: ReminderSounds.DEFAULT
                soundVm.setGeneral(generalSound)
            },
            onDismiss = { pickSound = false },
            vm = soundVm,
        )
    }

    pendingRestore?.let { backup ->
        val date = Instant.ofEpochMilli(backup.exportedAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                Text(stringResource(R.string.backup_restore_message, backup.medicines.size, backup.doses.size, date))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { vm.backup.restore(backup) } }
                            .onSuccess {
                                generalSound = AppSettings.reminderSound(context)
                                toast(context.getString(R.string.backup_restored))
                            }
                            .onFailure(::failed)
                    }
                }) { Text(stringResource(R.string.backup_restore_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.wizard_cancel)) }
            },
        )
    }
}

/** "Automatic backup" row: choose a file once, then a copy is rewritten daily. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoBackupItem(
    status: AutoBackup.Status,
    running: Boolean,
    onChooseFile: () -> Unit,
    onBackupNow: () -> Unit,
    onTurnOff: () -> Unit,
) {
    val supporting = when {
        !status.enabled -> stringResource(R.string.auto_backup_off_sub)
        running -> stringResource(R.string.auto_backup_running)
        status.lastError != null -> stringResource(R.string.auto_backup_error, status.lastError)
        status.lastAtMillis != null -> stringResource(
            R.string.auto_backup_last,
            Instant.ofEpochMilli(status.lastAtMillis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)),
        )
        else -> stringResource(R.string.auto_backup_pending)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.auto_backup_title)) },
        supportingContent = {
            Text(
                supporting,
                color = if (status.lastError != null && status.enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Outlined.Backup, null) },
        modifier = if (!status.enabled) Modifier.clickable(onClick = onChooseFile) else Modifier,
    )
    if (status.enabled) {
        FlowRow(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalButton(onClick = onBackupNow, enabled = !running) {
                Text(stringResource(R.string.auto_backup_now))
            }
            TextButton(onClick = onChooseFile) { Text(stringResource(R.string.auto_backup_change)) }
            TextButton(
                onClick = onTurnOff,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.auto_backup_turn_off)) }
        }
    }
}
