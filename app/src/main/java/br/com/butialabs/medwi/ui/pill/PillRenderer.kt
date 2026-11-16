package br.com.butialabs.medwi.ui.pill

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Renders a medication drawing outside Compose (notification large icon, widget). */
object PillRenderer {

    /**
     * [shapeKey]/[color]/[color2] as stored on the medicine. [background] fills a circle
     * behind the drawing (transparent = none).
     */
    fun bitmap(
        shapeKey: String?,
        color: String?,
        color2: String?,
        sizePx: Int,
        background: Color = Color.Transparent,
    ): Bitmap {
        val shape = PillShape.fromKey(shapeKey)
        val image = ImageBitmap(sizePx, sizePx)
        val size = Size(sizePx.toFloat(), sizePx.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), size) {
            if (background != Color.Transparent) drawCircle(background)
            val inset = sizePx * 0.12f
            drawContext.transform.inset(inset, inset, inset, inset)
            drawPill(
                shape,
                PillColors.parse(color, shape.defaultColor),
                PillColors.parse(color2, shape.defaultColor2),
            )
            drawContext.transform.inset(-inset, -inset, -inset, -inset)
        }
        return image.asAndroidBitmap()
    }
}
