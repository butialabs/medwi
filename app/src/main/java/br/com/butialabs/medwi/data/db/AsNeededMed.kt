package br.com.butialabs.medwi.data.db

/** An "as needed" medication (no fixed schedule), for the Today extra-dose section. */
data class AsNeededMed(
    val groupId: Long,
    val medicineId: Long,
    val dosageValue: Float,
    val dosageUnit: String,
    val name: String,
    val iconKey: String?,
    val colorHex: String?,
    val color2Hex: String? = null,
    val strengthValue: String? = null,
    val strengthUnit: String? = null,
)
