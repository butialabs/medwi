package br.com.butialabs.medwi.ui.addmed

/** A strength found inside a medicine name: "Euthyrox 125mg" -> ("Euthyrox", "125", "mg"). */
data class ParsedStrength(val name: String, val value: String, val unit: String)

private val STRENGTH = Regex(
    """(\d{1,3}(?:[.\s]\d{3})+|\d+(?:[.,]\d+)?)\s*""" +
        """(mg\s*/\s*\d*\s*ml|mcg\s*/\s*\d*\s*ml|mcg|µg|mg|g|ml|u\.?i\.?|iu|%|meq)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)

/**
 * Finds the first strength in [text] ("125mg", "10 mg", "250mg/5ml", "7.000 UI", "2,5%")
 * and returns the name without it plus the normalized value/unit; null if there is none.
 */
fun parseStrength(text: String): ParsedStrength? {
    val m = STRENGTH.find(text) ?: return null
    val rawValue = m.groupValues[1]
    val value = when {
        // 7.000 / 7 000 -> thousands separators
        Regex("""\d{1,3}(?:[.\s]\d{3})+""").matches(rawValue) -> rawValue.replace(".", "").replace(" ", "")
        else -> rawValue.replace('.', ',').let { if (it.endsWith(",0")) it.dropLast(2) else it }
    }
    val unit = normalizeUnit(m.groupValues[2])
    val name = (text.substring(0, m.range.first) + " " + text.substring(m.range.last + 1))
        .replace(Regex("""\s+"""), " ")
        .trim()
        .trim('-', ',', '/', ' ')
    if (name.isBlank()) return null
    return ParsedStrength(name, value, unit)
}

private fun normalizeUnit(raw: String): String {
    val u = raw.lowercase().replace(" ", "")
    return when {
        u == "µg" || u == "mcg" -> "mcg"
        u == "iu" || u.startsWith("u") -> "UI"
        u == "meq" -> "mEq"
        u.contains("/") -> u // mg/ml, mg/5ml, mcg/ml
        else -> u
    }
}
