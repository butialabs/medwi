package br.com.butialabs.medwi.data.db

/** Projection joining a dose with its medicine, for list screens. */
data class DoseView(
    val itemId: Long,
    val groupId: Long,
    val scheduledAtMillis: Long,
    val actualAtMillis: Long? = null,
    val note: String? = null,
    val dosageValue: Float,
    val dosageUnit: String,
    val status: String,
    val medicineName: String,
    val form: String?,
    val strengthValue: String?,
    val strengthUnit: String?,
    val iconKey: String?,
    val colorHex: String?,
    val color2Hex: String? = null,
    /** The medicine's own reminder sound key; null = the general sound. */
    val reminderSound: String? = null,
)
