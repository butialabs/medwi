package br.com.butialabs.medwi.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import br.com.butialabs.medwi.ui.addmed.DoseUnit
import br.com.butialabs.medwi.ui.addmed.MedForm
import br.com.butialabs.medwi.ui.addmed.qtyText

/** "125 mg" / "10 mg/ml"; null when the strength wasn't informed. */
fun strengthText(value: String?, unit: String?): String? {
    val v = value?.trim().orEmpty()
    if (v.isEmpty()) return null
    return listOf(v, unit?.trim().orEmpty()).filter { it.isNotEmpty() }.joinToString(" ")
}

/** "Euthyrox 125 mg": the medicine name with its strength, as shown everywhere. */
fun medTitle(name: String, strengthValue: String?, strengthUnit: String?): String =
    listOfNotNull(name, strengthText(strengthValue, strengthUnit)).joinToString(" ")

/**
 * "1 comprimido", "2 comprimidos", "10 ml", "2 gotas". Unknown/legacy tokens fall back
 * to the form label or the raw token.
 */
fun doseText(res: Resources, value: Float, unit: String?): String {
    val qty = value.qtyText()
    DoseUnit.fromToken(unit)?.let { u ->
        val count = if (value == 1f) 1 else 2 // plural rule by whole-unit count; 1,5 -> plural
        return res.getQuantityString(u.pluralsRes, count, qty)
    }
    val label = MedForm.fromToken(unit)?.let { res.getString(it.labelRes) } ?: unit.orEmpty()
    return "$qty $label".trim()
}

@Composable
fun doseText(value: Float, unit: String?): String = doseText(LocalContext.current.resources, value, unit)
