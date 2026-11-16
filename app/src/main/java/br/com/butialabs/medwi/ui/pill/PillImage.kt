package br.com.butialabs.medwi.ui.pill

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** Draws the medicine's shape in its colors, filling [modifier]'s bounds. */
@Composable
fun PillImage(shapeKey: String?, color: String?, color2: String?, modifier: Modifier = Modifier) {
    val shape = remember(shapeKey) { PillShape.fromKey(shapeKey) }
    val c1 = remember(color, shape) { PillColors.parse(color, shape.defaultColor) }
    val c2 = remember(color2, shape) { PillColors.parse(color2, shape.defaultColor2) }
    Canvas(modifier) { drawPill(shape, c1, c2) }
}
