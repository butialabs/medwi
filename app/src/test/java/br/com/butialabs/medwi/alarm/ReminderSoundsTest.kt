package br.com.butialabs.medwi.alarm

import android.content.Context
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import br.com.butialabs.medwi.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderSoundsTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun slot_sound_uses_the_medicine_sound_when_shared() {
        assertEquals("g", ReminderSounds.slotSound(listOf(null), "g"))
        assertEquals("medwi:bell", ReminderSounds.slotSound(listOf("medwi:bell"), "g"))
        assertEquals("medwi:bell", ReminderSounds.slotSound(listOf("medwi:bell", "medwi:bell"), "g"))
        // A medicine set explicitly to the general sound counts as the same sound.
        assertEquals("g", ReminderSounds.slotSound(listOf("g", null), "g"))
    }

    @Test
    fun slot_sound_falls_back_to_general_when_mixed() {
        assertEquals("g", ReminderSounds.slotSound(listOf("medwi:bell", null), "g"))
        assertEquals("g", ReminderSounds.slotSound(listOf("medwi:bell", "silent"), "g"))
        assertEquals("silent", ReminderSounds.slotSound(listOf("silent", "silent"), "g"))
    }

    @Test
    fun system_keys_map_picker_results() {
        assertEquals(ReminderSounds.SILENT, ReminderSounds.systemKey(null))
        assertEquals(ReminderSounds.DEFAULT, ReminderSounds.systemKey(Settings.System.DEFAULT_NOTIFICATION_URI))
        val uri = Uri.parse("content://media/internal/audio/media/12")
        assertEquals("system:$uri", ReminderSounds.systemKey(uri))
        assertEquals(uri, ReminderSounds.uriFor(ctx, "system:$uri"))
    }

    @Test
    fun missing_custom_sound_falls_back_to_general_then_default() {
        assertFalse(ReminderSounds.isAvailable(ctx, "custom:gone"))
        AppSettings.setReminderSound(ctx, "medwi:soft")
        assertEquals("medwi:soft", ReminderSounds.effective(ctx, "custom:gone"))
        AppSettings.setReminderSound(ctx, "custom:gone")
        assertEquals(ReminderSounds.DEFAULT, ReminderSounds.effective(ctx, "custom:gone"))
    }

    @Test
    fun built_in_sounds_resolve_to_named_raw_resources() {
        ReminderSounds.BUILT_INS.forEach { s ->
            assertTrue(ReminderSounds.isAvailable(ctx, s.key))
            val uri = ReminderSounds.uriFor(ctx, s.key).toString()
            assertTrue(uri, uri.endsWith("/raw/medwi_${s.id}"))
        }
        assertNull(ReminderSounds.uriFor(ctx, ReminderSounds.SILENT))
    }
}
