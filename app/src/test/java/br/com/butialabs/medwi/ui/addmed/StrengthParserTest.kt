package br.com.butialabs.medwi.ui.addmed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrengthParserTest {

    private fun check(input: String, name: String, value: String, unit: String) {
        assertEquals(ParsedStrength(name, value, unit), parseStrength(input))
    }

    @Test fun glued_mg() = check("Euthyrox 125mg", "Euthyrox", "125", "mg")
    @Test fun spaced_mg() = check("Lexapro 10 mg", "Lexapro", "10", "mg")
    @Test fun per_volume() = check("Amoxicilina 250mg/5ml suspensão", "Amoxicilina suspensão", "250", "mg/5ml")
    @Test fun mg_per_ml() = check("Dipirona 500 mg/ml gotas", "Dipirona gotas", "500", "mg/ml")
    @Test fun thousands_ui() = check("Vitamina D 7.000 UI", "Vitamina D", "7000", "UI")
    @Test fun decimal_comma() = check("Rivotril 2,5mg", "Rivotril", "2,5", "mg")
    @Test fun decimal_dot_becomes_comma() = check("Xarelto 2.5 mg", "Xarelto", "2,5", "mg")
    @Test fun micrograms() = check("Puran T4 88 mcg", "Puran T4", "88", "mcg")
    @Test fun milliliters() = check("Xarope 10ml", "Xarope", "10", "ml")
    @Test fun percent() = check("Hidrocortisona creme 1%", "Hidrocortisona creme", "1", "%")

    @Test fun no_strength() = assertNull(parseStrength("Losartana"))
    @Test fun number_without_unit_is_not_a_strength() = assertNull(parseStrength("Vitamina B12"))
    @Test fun strength_only_is_not_a_name() = assertNull(parseStrength("125 mg"))

    @Test
    fun draft_pulls_strength_out_of_the_name_once() {
        val d = MedDraft(name = "Euthyrox 125mg").withStrengthFromName()
        assertEquals("Euthyrox", d.name)
        assertEquals("125", d.strengthValue)
        assertEquals("mg", d.strengthUnit)
        // an informed strength is never overwritten
        val kept = MedDraft(name = "Euthyrox 50mg", strengthValue = "125").withStrengthFromName()
        assertEquals("Euthyrox 50mg", kept.name)
    }
}
