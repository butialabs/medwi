package br.com.butialabs.medwi.ui.addmed

import androidx.annotation.PluralsRes
import br.com.butialabs.medwi.R

/**
 * Unit of one dose ("1 comprimido", "10 ml", "2 gotas", "12 UI"), stored as [token] in
 * `schedule_group.dosageUnit` / `dose_item.dosageUnit`. Labels are plurals.
 */
enum class DoseUnit(val token: String, @PluralsRes val pluralsRes: Int) {
    PILL("pill", R.plurals.unit_pill),
    CAPSULE("capsule", R.plurals.unit_capsule),
    ML("ml", R.plurals.unit_ml),
    DROP("drop", R.plurals.unit_drop),
    PUFF("puff", R.plurals.unit_puff),
    UNIT("unit", R.plurals.unit_unit),
    SACHET("sachet", R.plurals.unit_sachet),
    APPLICATION("application", R.plurals.unit_application),
    PATCH("patch", R.plurals.unit_patch),
    SUPPOSITORY("suppository", R.plurals.unit_suppository),
    AMPOULE("ampoule", R.plurals.unit_ampoule),
    SPOON("spoon", R.plurals.unit_spoon),
    MG("mg", R.plurals.unit_mg);

    companion object {
        fun fromToken(token: String?): DoseUnit? = entries.firstOrNull { it.token == token }
    }
}
