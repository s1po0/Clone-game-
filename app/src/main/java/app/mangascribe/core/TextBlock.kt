package app.mangascribe.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A word or line found on a comic page, in page pixel coordinates.
 *
 * [polygon] is the four-corner outline ML Kit returns (top-left, top-right, bottom-right,
 * bottom-left) and is kept because manga lettering is often tilted inside a balloon.
 * [angleDegrees] is the tilt of the text baseline in degrees, clockwise positive, already
 * normalised to (-90, 90] so a vertical Japanese column and a horizontal line both read as
 * small angles.
 */
data class TextBlock(
    val id: Int = 0,
    val text: String,
    val polygon: List<PointF>,
    val languageTag: String? = null,
    val angleDegrees: Float = 0f,
) {
    val bounds: RectF = RectF.bounding(polygon)

    /** True when the outline is taller than it is wide — the usual case for Japanese columns. */
    val vertical: Boolean get() = bounds.height > bounds.width * 1.15f

    /** Tilt, snapped: under 3 degrees is treated as straight so Latin pages stay level. */
    val textAngle: TextAngle
        get() = when {
            kotlin.math.abs(angleDegrees) < 3f -> TextAngle.HORIZONTAL
            kotlin.math.abs(angleDegrees) > 87f -> TextAngle.HORIZONTAL
            angleDegrees > 0f -> TextAngle.DOWN_RIGHT
            else -> TextAngle.UP_RIGHT
        }
}

enum class TextAngle { HORIZONTAL, DOWN_RIGHT, UP_RIGHT }

data class PointF(val x: Float, val y: Float)

data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun inset(dx: Float, dy: Float = dx) =
        RectF(left + dx, top + dy, right - dx, bottom - dy)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun intersects(other: RectF): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Share of the smaller box covered by the intersection, 0..1 — the overlap test's measure. */
    fun overlapRatio(other: RectF): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val small = min(area, other.area)
        return if (small <= 0f) 0f else (w * h) / small
    }

    fun union(other: RectF) = RectF(
        min(left, other.left), min(top, other.top),
        max(right, other.right), max(bottom, other.bottom)
    )

    fun clampTo(width: Float, height: Float): RectF {
        val w = min(this.width, width)
        val h = min(this.height, height)
        val l = left.coerceIn(0f, max(0f, width - w))
        val t = top.coerceIn(0f, max(0f, height - h))
        return RectF(l, t, l + w, t + h)
    }

    companion object {
        val EMPTY = RectF(0f, 0f, 0f, 0f)

        fun bounding(points: List<PointF>): RectF {
            if (points.isEmpty()) return EMPTY
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (p in points) {
                l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
            }
            return RectF(l, t, r, b)
        }
    }
}

object Geometry {
    /**
     * Baseline tilt of an outline, in degrees, clockwise positive, normalised to (-90, 90].
     * Works for a tilted horizontal line and for a vertical Japanese column alike, because it
     * always returns the angle of the edge closest to horizontal.
     */
    fun angleOf(points: List<PointF>): Float {
        if (points.size < 2) return 0f
        val p0 = points[0]
        val p1 = points[1]
        val p3 = points[points.size - 1]
        val topEdge = atan2((p1.y - p0.y).toDouble(), (p1.x - p0.x).toDouble())
        val leftEdge = atan2((p3.y - p0.y).toDouble(), (p3.x - p0.x).toDouble())
        // take the edge that is within 45 degrees of horizontal
        val top = Math.toDegrees(topEdge)
        val left = Math.toDegrees(leftEdge) - 90.0
        val raw = if (kotlin.math.abs(normaliseDegrees(top)) <= kotlin.math.abs(normaliseDegrees(left))) {
            normaliseDegrees(top)
        } else {
            normaliseDegrees(left)
        }
        return raw.toFloat()
    }

    private fun normaliseDegrees(d: Double): Double {
        var a = d % 180.0
        if (a > 90.0) a -= 180.0
        if (a <= -90.0) a += 180.0
        return a
    }

    fun center(points: List<PointF>): PointF {
        if (points.isEmpty()) return PointF(0f, 0f)
        var x = 0f
        var y = 0f
        for (p in points) { x += p.x; y += p.y }
        return PointF(x / points.size, y / points.size)
    }

    fun distance(a: PointF, b: PointF): Float = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    /**
     * Second-longest edge of a quad: how tall a text line is, used to pick a starting font size.
     * Falls back to the shorter side of the bounding box for degenerate outlines.
     */
    fun fontSeedSize(block: TextBlock): Float {
        val b = block.bounds
        val edge = if (block.polygon.size >= 4) {
            val w = distance(block.polygon[0], block.polygon[1])
            val h = distance(block.polygon[1], block.polygon[2])
            if (block.vertical) min(w, h).coerceAtLeast(0.35f * max(w, h)) else min(w, h)
        } else {
            min(b.width, b.height)
        }
        return edge.coerceIn(6f, 512f)
    }

    /** Rotate [points] about [origin] by [degrees] (clockwise positive, screen coordinates). */
    fun rotate(points: List<PointF>, degrees: Float, origin: PointF): List<PointF> {
        if (degrees == 0f) return points
        val rad = degrees / 180.0 * PI
        val c = cos(rad)
        val s = sin(rad)
        return points.map { p ->
            val dx = p.x - origin.x
            val dy = p.y - origin.y
            PointF((origin.x + dx * c - dy * s).toFloat(), (origin.y + dx * s + dy * c).toFloat())
        }
    }
}
