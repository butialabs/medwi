package br.com.butialabs.medwi.ui.addmed

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import br.com.butialabs.medwi.ui.theme.MedwiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose tests for the wizard step contents, treating each step's `onChange`
 * callback as the contract (selection/typing emits an updated [MedDraft]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AddMedStepsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun form_step_selects_tapped_form() {
        var captured: MedDraft? = null
        rule.setContent {
            MedwiTheme { Column { FormContent(MedDraft()) { captured = it } } }
        }

        rule.onNodeWithText("Injection").performClick()

        assertEquals(MedForm.INJECTION, captured?.form)
    }

    @Test
    fun frequency_step_selects_tapped_choice() {
        var captured: MedDraft? = null
        rule.setContent {
            MedwiTheme { Column { FrequencyContent(MedDraft()) { captured = it } } }
        }

        rule.onNodeWithText("Every other day").performClick()

        assertEquals(FrequencyChoice.ALTERNATE_DAYS, captured?.frequency)
    }

    @Test
    fun name_step_emits_typed_name() {
        var captured: MedDraft? = null
        rule.setContent {
            MedwiTheme { Column { NameContent(MedDraft(), emptyList(), {}, { captured = it }) } }
        }

        rule.onNode(hasSetTextAction()).performTextInput("Aspirin")

        assertEquals("Aspirin", captured?.name)
    }
}
