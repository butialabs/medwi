package br.com.butialabs.medwi.ui.pill

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import br.com.butialabs.medwi.data.db.MedicineEntity
import br.com.butialabs.medwi.data.db.ScheduleGroupEntity
import br.com.butialabs.medwi.ui.addmed.DoseUnit
import br.com.butialabs.medwi.ui.addmed.MedDraft
import br.com.butialabs.medwi.ui.addmed.MedForm
import br.com.butialabs.medwi.ui.doseText
import br.com.butialabs.medwi.ui.medTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/** Drawings, dose units/plurals and form defaults. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PillAppearanceTest {

    private fun ctx(locale: Locale): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val conf = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(conf)
    }

    @Test
    fun every_shape_renders_with_visible_pixels() {
        PillShape.entries.forEach { shape ->
            val bmp = PillRenderer.bitmap(shape.key, shape.defaultColor, shape.defaultColor2, 96)
            var opaque = 0
            for (x in 0 until 96 step 4) for (y in 0 until 96 step 4) if ((bmp.getPixel(x, y) ushr 24) > 0) opaque++
            assertTrue("${shape.key} drew nothing", opaque > 10)
        }
    }

    @Test
    fun legacy_icon_keys_map_to_shapes() {
        assertEquals(PillShape.ROUND, PillShape.fromKey("pill"))
        assertEquals(PillShape.ROUND, PillShape.fromKey(null))
        assertEquals(PillShape.CAPSULE, PillShape.fromKey("capsule"))
        assertEquals(PillShape.DROPPER, PillShape.fromKey("drop"))
        assertEquals(PillShape.SYRINGE, PillShape.fromKey("syringe"))
        assertEquals(PillShape.HEART, PillShape.fromKey("heart"))
        assertEquals(PillShape.ROUND, PillShape.fromKey("unknown"))
    }

    @Test
    fun dose_text_uses_plurals_in_portuguese() {
        val res = ctx(Locale("pt", "BR")).resources
        assertEquals("1 comprimido", doseText(res, 1f, "pill"))
        assertEquals("2 comprimidos", doseText(res, 2f, "pill"))
        assertEquals("1.5 comprimidos", doseText(res, 1.5f, "pill"))
        assertEquals("10 ml", doseText(res, 10f, "ml"))
        assertEquals("2 gotas", doseText(res, 2f, "drop"))
        assertEquals("12 UI", doseText(res, 12f, "unit"))
    }

    @Test
    fun dose_text_in_english_and_legacy_tokens() {
        val res = ctx(Locale.US).resources
        assertEquals("1 pill", doseText(res, 1f, "pill"))
        assertEquals("3 puffs", doseText(res, 3f, "puff"))
        // a token from before v5 that the migration didn't know: shown as-is
        assertEquals("2 caps", doseText(res, 2f, "caps"))
    }

    @Test
    fun med_title_joins_name_and_strength() {
        assertEquals("Euthyrox 125 mg", medTitle("Euthyrox", "125", "mg"))
        assertEquals("Losartana", medTitle("Losartana", "", "mg"))
        assertEquals("Losartana", medTitle("Losartana", null, null))
    }

    @Test
    fun form_sets_dose_unit_strength_unit_and_drawing_until_user_overrides() {
        val syrup = MedDraft().withForm(MedForm.SOLUTION)
        assertEquals(DoseUnit.ML.token, syrup.doseUnit)
        assertEquals("mg/ml", syrup.strengthUnit)
        assertEquals(PillShape.BOTTLE.key, syrup.iconKey)
        assertEquals(PillColors.AMBER, syrup.colorHex)

        val custom = MedDraft().copy(colorHex = PillColors.PINK, appearanceTouched = true, doseUnit = "capsule", doseUnitTouched = true)
            .withForm(MedForm.SOLUTION)
        assertEquals(PillColors.PINK, custom.colorHex)
        assertEquals("capsule", custom.doseUnit)
    }

    @Test
    fun appearance_and_dose_unit_round_trip() {
        val d = MedDraft(name = "Omeprazol", iconKey = "capsule", colorHex = PillColors.PURPLE, color2Hex = PillColors.PINK, doseUnit = "capsule")
        val med = d.toMedicine()
        assertEquals(PillColors.PINK, med.color2)
        val group = ScheduleGroupEntity(medicineId = 1, dosageUnit = "capsule", scheduleType = "DAILY", consumptionTimes = "08:00", startDateEpochDay = 0)
        val back = MedDraft.fromExisting(med, group)
        assertEquals("capsule", back.iconKey)
        assertEquals(PillColors.PURPLE, back.colorHex)
        assertEquals(PillColors.PINK, back.color2Hex)
        assertEquals("capsule", back.doseUnit)
        assertTrue(back.appearanceTouched)
        // a medicine saved with an old generic icon key opens as a drawing
        assertEquals("round", MedDraft.fromExisting(MedicineEntity(name = "X", iconKey = "pill"), group).iconKey)
    }
}
