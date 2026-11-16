package br.com.butialabs.medwi.ui.pill

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import br.com.butialabs.medwi.R

/** Real-world pill / packaging colors offered in the appearance picker. */
object PillColors {
    const val WHITE = "#FAFAF7"
    const val CREAM = "#F3E7C4"
    const val YELLOW = "#FFD54F"
    const val ORANGE = "#FF9F43"
    const val PINK = "#F7A8C4"
    const val RED = "#E53935"
    const val PURPLE = "#8E6CC8"
    const val LIGHT_BLUE = "#8FD3F7"
    const val BLUE = "#1E88E5"
    const val LIGHT_GREEN = "#A8D8A0"
    const val GREEN = "#43A047"
    const val BROWN = "#8D6E63"
    const val AMBER = "#C77A1A"
    const val SKIN = "#E7C3A0"
    const val GRAY = "#9EA59E"
    const val BLACK = "#3A4440"

    data class Swatch(val hex: String, @StringRes val labelRes: Int)

    val all = listOf(
        Swatch(WHITE, R.string.color_white),
        Swatch(CREAM, R.string.color_cream),
        Swatch(YELLOW, R.string.color_yellow),
        Swatch(ORANGE, R.string.color_orange),
        Swatch(PINK, R.string.color_pink),
        Swatch(RED, R.string.color_red),
        Swatch(PURPLE, R.string.color_purple),
        Swatch(LIGHT_BLUE, R.string.color_light_blue),
        Swatch(BLUE, R.string.color_blue),
        Swatch(LIGHT_GREEN, R.string.color_light_green),
        Swatch(GREEN, R.string.color_green),
        Swatch(BROWN, R.string.color_brown),
        Swatch(AMBER, R.string.color_amber),
        Swatch(SKIN, R.string.color_skin),
        Swatch(GRAY, R.string.color_gray),
        Swatch(BLACK, R.string.color_black),
    )

    /** Parse a stored "#RRGGBB" (falls back to [fallback] for null/invalid values). */
    fun parse(hex: String?, fallback: String = WHITE): Color {
        val v = (hex ?: fallback).removePrefix("#")
        val argb = v.toLongOrNull(16) ?: fallback.removePrefix("#").toLong(16)
        return Color(if (v.length <= 6) 0xFF000000 or argb else argb)
    }
}
