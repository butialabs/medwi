package br.com.butialabs.medwi.ui.pill

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import br.com.butialabs.medwi.R

/** What kind of product a drawing represents; used to sort the picker by the chosen form. */
enum class ShapeGroup { SOLID, LIQUID, DEVICE }

/**
 * Medication drawings (pill shapes, bottles, devices), drawn in code so they stay sharp
 * at any size and take any color. [twoTone] drawings use a second color (capsule half,
 * bottle cap, patch pad...). The [key] is what is stored in `medicine.iconKey`.
 */
enum class PillShape(
    val key: String,
    @StringRes val labelRes: Int,
    val group: ShapeGroup,
    val twoTone: Boolean,
    val defaultColor: String,
    val defaultColor2: String = PillColors.WHITE,
) {
    ROUND("round", R.string.shape_round, ShapeGroup.SOLID, false, PillColors.WHITE),
    ROUND_SCORED("round_scored", R.string.shape_round_scored, ShapeGroup.SOLID, false, PillColors.WHITE),
    OVAL("oval", R.string.shape_oval, ShapeGroup.SOLID, false, PillColors.WHITE),
    OBLONG("oblong", R.string.shape_oblong, ShapeGroup.SOLID, false, PillColors.WHITE),
    CAPSULE("capsule", R.string.shape_capsule, ShapeGroup.SOLID, true, PillColors.BLUE, PillColors.WHITE),
    SOFTGEL("softgel", R.string.shape_softgel, ShapeGroup.SOLID, false, PillColors.AMBER),
    TRIANGLE("triangle", R.string.shape_triangle, ShapeGroup.SOLID, false, PillColors.WHITE),
    SQUARE("square", R.string.shape_square, ShapeGroup.SOLID, false, PillColors.WHITE),
    DIAMOND("diamond", R.string.shape_diamond, ShapeGroup.SOLID, false, PillColors.BLUE),
    HEXAGON("hexagon", R.string.shape_hexagon, ShapeGroup.SOLID, false, PillColors.WHITE),
    HEART("heart", R.string.shape_heart, ShapeGroup.SOLID, false, PillColors.PINK),
    TEAR("tear", R.string.shape_tear, ShapeGroup.SOLID, false, PillColors.YELLOW),
    SACHET("sachet", R.string.shape_sachet, ShapeGroup.SOLID, true, PillColors.LIGHT_BLUE, PillColors.WHITE),
    BOTTLE("bottle", R.string.shape_bottle, ShapeGroup.LIQUID, true, PillColors.AMBER, PillColors.WHITE),
    DROPPER("dropper", R.string.shape_dropper, ShapeGroup.LIQUID, true, PillColors.AMBER, PillColors.WHITE),
    AMPOULE("ampoule", R.string.shape_ampoule, ShapeGroup.LIQUID, true, PillColors.LIGHT_BLUE, PillColors.RED),
    SPOON("spoon", R.string.shape_spoon, ShapeGroup.LIQUID, true, PillColors.RED, PillColors.GRAY),
    SYRINGE("syringe", R.string.shape_syringe, ShapeGroup.DEVICE, true, PillColors.LIGHT_BLUE, PillColors.WHITE),
    PEN("pen", R.string.shape_pen, ShapeGroup.DEVICE, true, PillColors.BLUE, PillColors.GRAY),
    INHALER("inhaler", R.string.shape_inhaler, ShapeGroup.DEVICE, true, PillColors.BLUE, PillColors.GRAY),
    NASAL_SPRAY("nasal_spray", R.string.shape_nasal_spray, ShapeGroup.DEVICE, true, PillColors.WHITE, PillColors.LIGHT_BLUE),
    OINTMENT("ointment", R.string.shape_ointment, ShapeGroup.DEVICE, true, PillColors.WHITE, PillColors.RED),
    PATCH("patch", R.string.shape_patch, ShapeGroup.DEVICE, true, PillColors.SKIN, PillColors.WHITE),
    SUPPOSITORY("suppository", R.string.shape_suppository, ShapeGroup.DEVICE, false, PillColors.CREAM);

    companion object {
        /** Stored key -> shape; also maps the old generic icon keys (pill, drop...). */
        fun fromKey(key: String?): PillShape = when (key) {
            null, "", "pill" -> ROUND
            "drop" -> DROPPER
            else -> entries.firstOrNull { it.key == key } ?: ROUND
        }
    }
}

/** Darker edge so light pills (white, cream) still read against light backgrounds. */
private fun outlineOf(c: Color): Color =
    if (c.luminance() > 0.7f) Color(0xFF9AA39A) else lerp(c, Color.Black, 0.35f)

/**
 * Draws [shape] centered in the current DrawScope, using [c1] (and [c2] for two-tone
 * drawings). Coordinates below are in a unit square scaled to the smaller side.
 */
fun DrawScope.drawPill(shape: PillShape, c1: Color, c2: Color) {
    val s = size.minDimension
    val ox = (size.width - s) / 2f
    val oy = (size.height - s) / 2f
    fun p(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
    fun sz(w: Float, h: Float) = Size(w * s, h * s)
    fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(p(l, t), p(r, b))
    val sw = s * 0.035f
    val stroke = Stroke(width = sw)
    val center = p(0.5f, 0.5f)

    fun fill(path: Path, c: Color) {
        drawPath(path, c)
        drawPath(path, outlineOf(c), style = stroke)
    }
    fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float, c: Color) {
        val rr = RoundRect(rect(l, t, r, b), CornerRadius(radius * s))
        fill(Path().apply { addRoundRect(rr) }, c)
    }
    fun oval(l: Float, t: Float, r: Float, b: Float, c: Color) =
        fill(Path().apply { addOval(rect(l, t, r, b)) }, c)
    fun polygon(c: Color, vararg pts: Pair<Float, Float>) {
        val path = Path().apply {
            moveTo(p(pts[0].first, pts[0].second).x, p(pts[0].first, pts[0].second).y)
            for (i in 1 until pts.size) lineTo(p(pts[i].first, pts[i].second).x, p(pts[i].first, pts[i].second).y)
            close()
        }
        fill(path, c)
    }
    /** Soft glossy highlight on solid pills. */
    fun shine(l: Float, t: Float, r: Float, b: Float) =
        drawOval(Color.White.copy(alpha = 0.35f), topLeft = p(l, t), size = sz(r - l, b - t))
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, c: Color, w: Float = sw) =
        drawLine(c, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Round)

    when (shape) {
        PillShape.ROUND -> {
            oval(0.14f, 0.14f, 0.86f, 0.86f, c1); shine(0.26f, 0.22f, 0.5f, 0.38f)
        }
        PillShape.ROUND_SCORED -> {
            oval(0.14f, 0.14f, 0.86f, 0.86f, c1)
            line(0.5f, 0.2f, 0.5f, 0.8f, outlineOf(c1))
            shine(0.24f, 0.24f, 0.42f, 0.38f)
        }
        PillShape.OVAL -> {
            oval(0.1f, 0.25f, 0.9f, 0.75f, c1); shine(0.22f, 0.32f, 0.5f, 0.44f)
        }
        PillShape.OBLONG -> rotate(-30f, center) {
            roundRect(0.08f, 0.32f, 0.92f, 0.68f, 0.18f, c1)
            line(0.5f, 0.36f, 0.5f, 0.64f, outlineOf(c1))
            shine(0.16f, 0.37f, 0.42f, 0.46f)
        }
        PillShape.CAPSULE -> rotate(-35f, center) {
            val r = CornerRadius(0.17f * s)
            val left = Path().apply {
                addRoundRect(RoundRect(rect(0.1f, 0.33f, 0.5f, 0.67f), r, CornerRadius.Zero, CornerRadius.Zero, r))
            }
            val right = Path().apply {
                addRoundRect(RoundRect(rect(0.5f, 0.33f, 0.9f, 0.67f), CornerRadius.Zero, r, r, CornerRadius.Zero))
            }
            fill(left, c1)
            fill(right, c2)
            shine(0.18f, 0.37f, 0.44f, 0.46f)
        }
        PillShape.SOFTGEL -> {
            oval(0.12f, 0.24f, 0.88f, 0.76f, c1.copy(alpha = 0.92f))
            shine(0.24f, 0.3f, 0.56f, 0.46f)
        }
        PillShape.TRIANGLE -> {
            polygon(c1, 0.5f to 0.14f, 0.88f to 0.82f, 0.12f to 0.82f); shine(0.4f, 0.42f, 0.56f, 0.54f)
        }
        PillShape.SQUARE -> {
            roundRect(0.18f, 0.18f, 0.82f, 0.82f, 0.12f, c1); shine(0.26f, 0.26f, 0.5f, 0.38f)
        }
        PillShape.DIAMOND -> {
            polygon(c1, 0.5f to 0.1f, 0.88f to 0.5f, 0.5f to 0.9f, 0.12f to 0.5f); shine(0.36f, 0.32f, 0.54f, 0.44f)
        }
        PillShape.HEXAGON -> {
            polygon(
                c1, 0.3f to 0.15f, 0.7f to 0.15f, 0.9f to 0.5f, 0.7f to 0.85f, 0.3f to 0.85f, 0.1f to 0.5f,
            )
            shine(0.28f, 0.26f, 0.52f, 0.38f)
        }
        PillShape.HEART -> {
            val path = Path().apply {
                val a = p(0.5f, 0.84f); moveTo(a.x, a.y)
                val c = listOf(
                    listOf(0.1f to 0.6f, 0.06f to 0.24f, 0.32f to 0.2f),
                    listOf(0.42f to 0.18f, 0.48f to 0.26f, 0.5f to 0.32f),
                    listOf(0.52f to 0.26f, 0.58f to 0.18f, 0.68f to 0.2f),
                    listOf(0.94f to 0.24f, 0.9f to 0.6f, 0.5f to 0.84f),
                )
                c.forEach { (c1p, c2p, e) ->
                    val q1 = p(c1p.first, c1p.second); val q2 = p(c2p.first, c2p.second); val q3 = p(e.first, e.second)
                    cubicTo(q1.x, q1.y, q2.x, q2.y, q3.x, q3.y)
                }
                close()
            }
            fill(path, c1); shine(0.22f, 0.28f, 0.4f, 0.38f)
        }
        PillShape.TEAR -> {
            val path = Path().apply {
                val a = p(0.5f, 0.1f); moveTo(a.x, a.y)
                val pts = listOf(
                    listOf(0.5f to 0.1f, 0.2f to 0.5f, 0.2f to 0.64f),
                    listOf(0.2f to 0.82f, 0.34f to 0.9f, 0.5f to 0.9f),
                    listOf(0.66f to 0.9f, 0.8f to 0.82f, 0.8f to 0.64f),
                    listOf(0.8f to 0.5f, 0.5f to 0.1f, 0.5f to 0.1f),
                )
                pts.forEach { (c1p, c2p, e) ->
                    val q1 = p(c1p.first, c1p.second); val q2 = p(c2p.first, c2p.second); val q3 = p(e.first, e.second)
                    cubicTo(q1.x, q1.y, q2.x, q2.y, q3.x, q3.y)
                }
                close()
            }
            fill(path, c1); shine(0.32f, 0.52f, 0.46f, 0.68f)
        }
        PillShape.SACHET -> {
            roundRect(0.2f, 0.14f, 0.8f, 0.86f, 0.04f, c1)
            drawRect(c2.copy(alpha = 0.85f), topLeft = p(0.2f, 0.14f), size = sz(0.6f, 0.09f))
            drawRect(c2.copy(alpha = 0.85f), topLeft = p(0.2f, 0.77f), size = sz(0.6f, 0.09f))
            line(0.32f, 0.4f, 0.68f, 0.4f, c2, sw * 1.4f)
            line(0.32f, 0.5f, 0.6f, 0.5f, c2, sw * 1.4f)
        }
        PillShape.BOTTLE -> {
            roundRect(0.37f, 0.08f, 0.63f, 0.22f, 0.03f, c2)
            roundRect(0.42f, 0.21f, 0.58f, 0.3f, 0.01f, lerp(c1, Color.Black, 0.2f))
            roundRect(0.25f, 0.28f, 0.75f, 0.92f, 0.09f, c1)
            drawRect(Color.White.copy(alpha = 0.85f), topLeft = p(0.25f, 0.5f), size = sz(0.5f, 0.22f))
            line(0.33f, 0.58f, 0.67f, 0.58f, c1, sw)
            line(0.33f, 0.65f, 0.58f, 0.65f, c1, sw)
        }
        PillShape.DROPPER -> {
            oval(0.4f, 0.06f, 0.6f, 0.3f, c2)
            roundRect(0.38f, 0.26f, 0.62f, 0.4f, 0.02f, c2)
            roundRect(0.3f, 0.38f, 0.7f, 0.92f, 0.08f, c1)
            drawRect(Color.White.copy(alpha = 0.85f), topLeft = p(0.3f, 0.56f), size = sz(0.4f, 0.18f))
        }
        PillShape.AMPOULE -> rotate(-20f, center) {
            oval(0.4f, 0.08f, 0.6f, 0.3f, c1.copy(alpha = 0.75f))
            roundRect(0.45f, 0.26f, 0.55f, 0.4f, 0.02f, c1.copy(alpha = 0.75f))
            roundRect(0.36f, 0.38f, 0.64f, 0.92f, 0.07f, c1.copy(alpha = 0.75f))
            line(0.43f, 0.34f, 0.57f, 0.34f, c2, sw * 1.6f)
            drawRect(Color.White.copy(alpha = 0.4f), topLeft = p(0.4f, 0.46f), size = sz(0.05f, 0.36f))
        }
        PillShape.SPOON -> rotate(-35f, center) {
            roundRect(0.46f, 0.46f, 0.94f, 0.54f, 0.04f, c2)
            oval(0.06f, 0.32f, 0.5f, 0.68f, c2)
            drawOval(c1, topLeft = p(0.11f, 0.37f), size = sz(0.34f, 0.26f))
        }
        PillShape.SYRINGE -> rotate(-45f, center) {
            line(0.04f, 0.5f, 0.2f, 0.5f, Color(0xFF8A8F8A), sw * 0.9f)
            roundRect(0.18f, 0.4f, 0.66f, 0.6f, 0.03f, c2)
            drawRect(c1, topLeft = p(0.2f, 0.425f), size = sz(0.26f, 0.15f))
            roundRect(0.64f, 0.33f, 0.68f, 0.67f, 0.01f, c2)
            roundRect(0.68f, 0.46f, 0.86f, 0.54f, 0.01f, Color(0xFFB0B5B0))
            roundRect(0.85f, 0.36f, 0.9f, 0.64f, 0.015f, Color(0xFFB0B5B0))
            listOf(0.3f, 0.38f, 0.46f, 0.54f).forEach { x -> line(x, 0.4f, x, 0.46f, outlineOf(c2), sw * 0.6f) }
        }
        PillShape.PEN -> rotate(-45f, center) {
            line(0.04f, 0.5f, 0.12f, 0.5f, Color(0xFF8A8F8A), sw * 0.9f)
            roundRect(0.1f, 0.39f, 0.74f, 0.61f, 0.08f, c1)
            roundRect(0.26f, 0.43f, 0.42f, 0.57f, 0.02f, Color.White.copy(alpha = 0.85f))
            roundRect(0.72f, 0.4f, 0.92f, 0.6f, 0.06f, c2)
        }
        PillShape.INHALER -> {
            roundRect(0.44f, 0.06f, 0.66f, 0.5f, 0.06f, c2)
            roundRect(0.34f, 0.3f, 0.72f, 0.8f, 0.08f, c1)
            roundRect(0.18f, 0.66f, 0.62f, 0.88f, 0.06f, c1)
            shine(0.4f, 0.36f, 0.5f, 0.56f)
        }
        PillShape.NASAL_SPRAY -> {
            polygon(c2, 0.45f to 0.08f, 0.55f to 0.08f, 0.59f to 0.34f, 0.41f to 0.34f)
            roundRect(0.3f, 0.32f, 0.7f, 0.42f, 0.03f, c2)
            roundRect(0.32f, 0.42f, 0.68f, 0.92f, 0.08f, c1)
            drawRect(c2.copy(alpha = 0.7f), topLeft = p(0.32f, 0.58f), size = sz(0.36f, 0.16f))
        }
        PillShape.OINTMENT -> rotate(-35f, center) {
            polygon(c1, 0.1f to 0.34f, 0.72f to 0.38f, 0.72f to 0.62f, 0.1f to 0.66f)
            roundRect(0.06f, 0.32f, 0.12f, 0.68f, 0.01f, lerp(c1, Color.Black, 0.1f))
            roundRect(0.7f, 0.4f, 0.9f, 0.6f, 0.04f, c2)
            line(0.3f, 0.44f, 0.56f, 0.45f, c2, sw * 1.4f)
        }
        PillShape.PATCH -> {
            roundRect(0.14f, 0.2f, 0.86f, 0.8f, 0.14f, c1)
            roundRect(0.3f, 0.34f, 0.7f, 0.66f, 0.08f, c2)
        }
        PillShape.SUPPOSITORY -> rotate(-30f, center) {
            val path = Path().apply {
                val a = p(0.36f, 0.88f); moveTo(a.x, a.y)
                val b = p(0.36f, 0.42f); lineTo(b.x, b.y)
                val q1 = p(0.36f, 0.14f); val q2 = p(0.64f, 0.14f); val q3 = p(0.64f, 0.42f)
                cubicTo(q1.x, q1.y, q2.x, q2.y, q3.x, q3.y)
                val d = p(0.64f, 0.88f); lineTo(d.x, d.y)
                close()
            }
            fill(path, c1); shine(0.41f, 0.3f, 0.5f, 0.5f)
        }
    }
}
