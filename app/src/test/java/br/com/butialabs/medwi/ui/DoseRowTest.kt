package br.com.butialabs.medwi.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import br.com.butialabs.medwi.data.db.DoseView
import br.com.butialabs.medwi.ui.theme.MedwiTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose UI tests for [DoseRow], run locally under Robolectric. Verifies the
 * rendered content and that the action buttons emit their callbacks, following
 * the "callbacks as test surfaces" pattern with text-based selectors.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DoseRowTest {

    @get:Rule
    val rule = createComposeRule()

    private fun dose(status: String) = DoseView(
        itemId = 1, groupId = 1, scheduledAtMillis = 1_700_000_000_000L,
        dosageValue = 1f, dosageUnit = "tablet", status = status,
        medicineName = "Aspirin", form = "tablet", strengthValue = "100", strengthUnit = "mg",
        iconKey = "round", colorHex = "#FAFAF7",
    )

    @Test
    fun pending_dose_renders_name_and_fires_take_callback() {
        var taken = false
        rule.setContent {
            MedwiTheme {
                DoseRow(dose("PENDING"), onTake = { taken = true }, onSnooze = {}, onSkip = {})
            }
        }

        rule.onNodeWithText("Aspirin · 100 mg").assertIsDisplayed()
        rule.onNodeWithText("Take").performClick()

        assertTrue("onTake should have been invoked", taken)
    }

    @Test
    fun taken_dose_hides_action_buttons() {
        rule.setContent {
            MedwiTheme {
                DoseRow(dose("TAKEN"), onTake = {}, onSnooze = {}, onSkip = {})
            }
        }

        rule.onNodeWithText("Aspirin · 100 mg").assertIsDisplayed()
        rule.onNodeWithText("Take").assertDoesNotExist()
        rule.onNodeWithText("Snooze").assertDoesNotExist()
    }
}
