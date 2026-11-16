package br.com.butialabs.medwi.ui.sound

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.butialabs.medwi.R
import br.com.butialabs.medwi.alarm.ReminderSounds
import br.com.butialabs.medwi.data.MedRepository
import br.com.butialabs.medwi.data.settings.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Saves sound choices through the repository (which also tidies notification channels). */
@HiltViewModel
class SoundViewModel @Inject constructor(private val repository: MedRepository) : ViewModel() {
    fun setGeneral(key: String) {
        viewModelScope.launch { repository.setGeneralSound(key) }
    }

    fun setMedicine(medicineId: Long, key: String?) {
        viewModelScope.launch { repository.setMedicineSound(medicineId, key) }
    }

    suspend fun deleteCustom(key: String) = repository.deleteCustomSound(key)
}

/** Label for a medicine's sound: its own sound's name, or "General sound". */
@Composable
fun medicineSoundLabel(key: String?): String {
    val context = LocalContext.current
    return if (key == null) stringResource(R.string.sound_general)
    else remember(key) { ReminderSounds.displayName(context, key) }
}

/** A settings-style row showing the current sound; opens the picker on tap. */
@Composable
fun SoundRow(value: String, onClick: () -> Unit, modifier: Modifier = Modifier, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.sound_title)) },
        supportingContent = { Text(supporting ?: value) },
        leadingContent = { Icon(Icons.Outlined.MusicNote, null) },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * Bottom sheet to choose a reminder sound. [perMedicine] adds "Use the general sound"
 * (selected = null). Tapping an option selects it and plays a preview; [onSelect] is
 * called right away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundPickerSheet(
    selected: String?,
    perMedicine: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
    vm: SoundViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customs by remember { mutableStateOf(ReminderSounds.listCustom(context)) }
    var importing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ReminderSounds.CustomSound?>(null) }
    val preview = remember { SoundPreview(context) }
    DisposableEffect(Unit) { onDispose { preview.stop() } }

    fun choose(key: String?) {
        onSelect(key)
        val play = key ?: AppSettings.reminderSound(context)
        preview.play(ReminderSounds.previewUri(context, ReminderSounds.effective(context, play)))
    }

    val phonePicker = rememberLauncherForActivityResult(PickPhoneSound()) { picked ->
        if (picked != null) choose(ReminderSounds.systemKey(picked.uri))
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ReminderSounds.importCustom(context, uri) } }
            importing = false
            result.onSuccess { key ->
                customs = ReminderSounds.listCustom(context)
                choose(key)
                Toast.makeText(context, R.string.sound_added, Toast.LENGTH_SHORT).show()
            }.onFailure { e ->
                val msg = when ((e as? ReminderSounds.ImportException)?.error) {
                    ReminderSounds.ImportError.TOO_LARGE -> R.string.sound_error_too_large
                    ReminderSounds.ImportError.TOO_LONG -> R.string.sound_error_too_long
                    else -> R.string.sound_error_invalid
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text(
                    stringResource(R.string.sound_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            if (perMedicine) {
                item {
                    val general = remember { ReminderSounds.displayName(context, AppSettings.reminderSound(context)) }
                    OptionRow(
                        title = stringResource(R.string.sound_use_general),
                        subtitle = stringResource(R.string.sound_use_general_sub, general),
                        selected = selected == null,
                        onClick = { choose(null) },
                    )
                }
            }

            item { Header(stringResource(R.string.sound_section_medwi)) }
            items(ReminderSounds.BUILT_INS, key = { it.key }) { s ->
                OptionRow(stringResource(s.nameRes), selected = selected == s.key, onClick = { choose(s.key) })
            }

            item { Header(stringResource(R.string.sound_section_phone)) }
            item {
                OptionRow(
                    stringResource(R.string.sound_phone_default),
                    selected = selected == ReminderSounds.DEFAULT,
                    onClick = { choose(ReminderSounds.DEFAULT) },
                )
            }
            if (ReminderSounds.isSystem(selected) && selected != ReminderSounds.DEFAULT) {
                item {
                    val name = remember(selected) { ReminderSounds.displayName(context, selected!!) }
                    OptionRow(name, selected = true, onClick = { choose(selected) })
                }
            }
            item {
                ActionRow(Icons.Outlined.LibraryMusic, stringResource(R.string.sound_pick_phone)) {
                    preview.stop()
                    val current = selected?.let { ReminderSounds.uriFor(context, it) }
                        .takeIf { ReminderSounds.isSystem(selected) }
                    phonePicker.launch(current)
                }
            }
            item {
                OptionRow(
                    stringResource(R.string.sound_silent),
                    selected = selected == ReminderSounds.SILENT,
                    onClick = { choose(ReminderSounds.SILENT) },
                )
            }

            item { Header(stringResource(R.string.sound_section_mine)) }
            items(customs, key = { it.key }) { s ->
                OptionRow(
                    s.name,
                    selected = selected == s.key,
                    onClick = { choose(s.key) },
                    trailing = {
                        IconButton(onClick = { confirmDelete = s }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.sound_delete))
                        }
                    },
                )
            }
            item {
                ActionRow(
                    Icons.Outlined.FileUpload,
                    stringResource(R.string.sound_upload),
                    stringResource(R.string.sound_upload_sub),
                    busy = importing,
                ) {
                    preview.stop()
                    upload.launch(arrayOf("audio/*"))
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = {
                        preview.stop()
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                    }) { Text(stringResource(R.string.sound_done)) }
                }
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.sound_delete_title, s.name)) },
            text = { Text(stringResource(R.string.sound_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    preview.stop()
                    scope.launch {
                        vm.deleteCustom(s.key)
                        customs = ReminderSounds.listCustom(context)
                        if (selected == s.key) onSelect(if (perMedicine) null else ReminderSounds.DEFAULT)
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.wizard_cancel)) }
            },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailing,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = {
            if (busy) CircularProgressIndicator(Modifier.padding(2.dp), strokeWidth = 2.dp)
            else Icon(icon, null, Modifier.padding(horizontal = 0.dp))
        },
        modifier = Modifier.clickable(enabled = !busy, onClick = onClick),
    )
}

/** Plays one sound at a time for previews. */
private class SoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null

    fun play(uri: Uri?) {
        stop()
        if (uri == null) return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { stop() }
                prepareAsync()
            }
        }.getOrNull()
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}

/** Result of the phone's ringtone picker; [uri] null = "None". Cancel yields no result. */
class PickedSound(val uri: Uri?)

/** The phone's own sound picker (notification sounds), with [Uri] = current selection. */
class PickPhoneSound : ActivityResultContract<Uri?, PickedSound?>() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, input)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, context.getString(R.string.sound_title))
        }

    override fun parseResult(resultCode: Int, intent: Intent?): PickedSound? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        return PickedSound(IntentCompat.getParcelableExtra(intent, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java))
    }
}
