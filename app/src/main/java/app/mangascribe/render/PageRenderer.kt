package app.mangascribe.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.mangascribe.core.Measurer
import app.mangascribe.core.RtlTypeset
import app.mangascribe.core.TextAngle
import app.mangascribe.core.TextFitter
import app.mangascribe.core.VerticalTypeset
import app.mangascribe.data.Balloon
import app.mangascribe.data.BalloonCodec
import kotlin.math.max
import kotlin.math.min

data class RenderStyle(
    /** paper colour sampled from the bubble, used to erase the original lettering */
    val paperColor: Int = Color.WHITE,
    val inkColor: Int = Color.BLACK,
    val fontScale: Float = 1f,
    val typeface: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL),
    val maskOriginal: Boolean = true,
    val outlineText: Boolean = false,
    val verticalTypesetting: Boolean = false,
    /**
     * Draw right to left: lines anchor on the bubble's right edge and wrap at word
     * boundaries that the Arabic-script shaper allows. On for Arabic, Persian and Kurdish.
     */
    val rightToLeft: Boolean = false,
)

/**
 * Draws the translated page. Two modes:
 *  - [renderInPlace] bakes the whole page into one bitmap, the reader's normal fast path;
 *  - [balloonsForOverlay] hands the reader editable, individually drawn balloons.
 * The original lettering is masked with the bubble's own paper colour so the page keeps its
 * shape instead of pasting white rectangles over dark panels.
 */
class PageRenderer {

    private val measurer = PaintMeasurer()

    fun renderInPlace(
        page: Bitmap?,
        balloons: List<Balloon>,
        style: RenderStyle,
        maxDimension: Int = 2400,
    ): Bitmap {
        val width = page?.width ?: 1080
        val height = page?.height ?: 1600
        val scale = min(1f, maxDimension.toFloat() / max(width, height))
        val out = Bitmap.createBitmap(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(out)
        if (page != null) {
            canvas.drawBitmap(page, null, RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()), null)
        } else {
            canvas.drawColor(Color.WHITE)
        }
        canvas.scale(scale, scale)
        for (balloon in balloons) {
            if (balloon.hidden || balloon.translatedText.isBlank()) continue
            drawBalloon(canvas, balloon, style)
        }
        return out
    }

    fun drawBalloon(canvas: Canvas, balloon: Balloon, style: RenderStyle) {
        val rect = balloon.movedRect()
        if (rect.width < 4f || rect.height < 4f) return
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (style.maskOriginal && balloon.maskOriginal) style.paperColor else Color.TRANSPARENT
            this.style = Paint.Style.FILL
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // A bubble's own paper colour often hides the original only partly; a soft
            // translucent coat around the tight box keeps the outline visible.
            color = withAlpha(style.paperColor, 210)
            this.style = Paint.Style.FILL
        }
        val pad = max(2f, min(rect.width, rect.height) * 0.08f)
        val box = RectF(
            rect.left - pad * 0.5f,
            rect.top - pad * 0.5f,
            rect.right + pad * 0.5f,
            rect.bottom + pad * 0.5f,
        )
        canvas.save()
        val pivotX = box.centerX()
        val pivotY = box.centerY()
        canvas.rotate(balloon.angleDegrees.coerceIn(-30f, 30f), pivotX, pivotY)

        if (balloon.maskOriginal && style.maskOriginal) {
            val radius = min(box.width(), box.height()) * 0.28f
            canvas.drawRoundRect(box, radius, radius, mask)
            // widen the mask slightly past the detected line so kerned edges do not show
            canvas.drawRoundRect(
                RectF(box.left - pad, box.top - pad, box.right + pad, box.bottom + pad),
                radius * 1.1f, radius * 1.1f, fill,
            )
        }

        val text = balloon.translatedText
        val targetWidth = box.width() - pad * 1.6f
        val targetHeight = box.height() - pad * 1.6f
        if (targetWidth <= 4f || targetHeight <= 4f) { canvas.restore(); return }

        val seed = if (balloon.vertical && style.verticalTypesetting) {
            // vertical columns: font size follows the column width, not the block height
            min(box.height() / 6f, box.width() * 0.42f)
        } else {
            targetHeight.coerceAtMost(targetWidth * 0.9f) * 0.9f
        } * style.fontScale

        val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.inkColor
            typeface = style.typeface
            textAlign = Paint.Align.CENTER
        }

        if (balloon.vertical && style.verticalTypesetting) {
            val charSize = max(9f, min(seed, targetWidth / 1.4f))
            val columns = VerticalTypeset.layout(text, targetWidth, targetHeight, charSize)
            inkPaint.textSize = charSize
            val step = charSize * 1.02f
            val totalWidth = columns.size * step
            val startX = box.centerX() - totalWidth / 2f + step / 2f
            columns.forEachIndexed { i, column ->
                val x = startX + i * step
                val columnHeight = column.text.length * step
                var y = box.centerY() - columnHeight / 2f + step * 0.8f
                for (ch in column.text) {
                    if (ch != ' ') drawWithOutline(canvas, ch.toString(), x, y, inkPaint, style)
                    y += step
                }
            }
        } else if (style.rightToLeft) {
            drawRightToLeft(canvas, text, box, targetWidth, targetHeight, seed, inkPaint, style)
        } else {
            val result = TextFitter.fit(
                text = text,
                boxWidth = targetWidth,
                boxHeight = targetHeight,
                startSizePx = max(9f, seed),
                measurer = measurer,
                minSizePx = if (targetHeight < 40f) 7f else 9f,
            )
            inkPaint.textSize = result.fontSize
            val lineHeight = result.lineHeight
            val totalHeight = result.lines.size * lineHeight
            var y = box.centerY() - totalHeight / 2f + lineHeight * 0.78f
            for (line in result.lines) {
                // bubbles read centred; long captions keep the detected block's centre
                drawWithOutline(canvas, line, box.centerX(), y, inkPaint, style)
                y += lineHeight
            }
        }
        canvas.restore()
    }

    /**
     * Right-to-left ink for Arabic, Persian and Kurdish (Sorani). Two differences from the
     * Latin path, both visible to a reader who knows the script:
     *  - each line is right-aligned to the bubble's edge, because a half-full line in an RTL
     *    paragraph starts at the right;
     *  - wrapping uses [RtlTypeset.wrap], which never splits a joined word, and a short line
     *    is filled with kashida before the font is shrunk any further.
     */
    private fun drawRightToLeft(
        canvas: Canvas,
        text: String,
        box: RectF,
        targetWidth: Float,
        targetHeight: Float,
        seed: Float,
        inkPaint: Paint,
        style: RenderStyle,
    ) {
        val clean = RtlTypeset.normalisePunctuation(text)
        var size = max(9f, seed)
        var lines = RtlTypeset.wrap(clean, targetWidth, size, measurer)
        var guard = 0
        while (guard++ < 24 && lines.size * measurer.lineHeight(size) > targetHeight && size > 8f) {
            size = (size * 0.92f).coerceAtLeast(8f)
            lines = RtlTypeset.wrap(clean, targetWidth, size, measurer)
        }
        inkPaint.textSize = size
        inkPaint.textAlign = Paint.Align.RIGHT
        val lineHeight = measurer.lineHeight(size)
        val totalHeight = lines.size * lineHeight
        var y = box.centerY() - totalHeight / 2f + lineHeight * 0.78f
        val right = box.right - (box.width() - targetWidth) / 2f
        for (line in lines) {
            var draw = line
            // fill a short single-line bubble with kashida after the last joining letter
            if (lines.size == 1) {
                val kashida = RtlTypeset.kashidaCount(measurer.width(line, size), targetWidth, size)
                if (kashida > 0) draw = withKashida(line, kashida)
            }
            drawWithOutline(canvas, draw, right, y, inkPaint, style)
            y += lineHeight
        }
        inkPaint.textAlign = Paint.Align.CENTER
    }

    /** Insert tatweel after a joining letter near the middle, so the stretch looks natural. */
    private fun withKashida(line: String, count: Int): String {
        val joiner = "بتثنيسشصضطظعغفقكلمهیکگچپژڤ"
        var index = line.indices.firstOrNull { joiner.contains(line[it]) } ?: return line
        // prefer the last joining letter, which keeps the stretch away from the first word
        line.indices.lastOrNull { joiner.contains(line[it]) }?.let { index = it }
        return line.substring(0, index + 1) + "\u0640".repeat(count) + line.substring(index + 1)
    }

    private fun drawWithOutline(canvas: Canvas, line: String, x: Float, y: Float, paint: Paint, style: RenderStyle) {
        if (!style.outlineText) { canvas.drawText(line, x, y, paint); return }
        val stroke = Paint(paint).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = max(1.5f, paint.textSize * 0.12f)
            color = Color.WHITE
        }
        canvas.drawText(line, x, y, stroke)
        canvas.drawText(line, x, y, paint)
    }

    /**
     * Estimates the paper colour of a bubble by sampling the border of its box and taking the
     * median — the inside is lettering, the border is the bubble itself.
     */
    fun samplePaperColor(page: Bitmap?, balloon: Balloon): Int {
        if (page == null) return Color.WHITE
        val r = balloon.movedRect()
        val samples = mutableListOf<Int>()
        val steps = 12
        val left = r.left.coerceIn(0f, page.width - 1f).toInt()
        val right = r.right.coerceIn(0f, page.width - 1f).toInt()
        val top = r.top.coerceIn(0f, page.height - 1f).toInt()
        val bottom = r.bottom.coerceIn(0f, page.height - 1f).toInt()
        if (right - left < 2 || bottom - top < 2) return Color.WHITE
        for (i in 0 until steps) {
            val x = left + (right - left) * i / (steps - 1)
            samples += page.getPixel(x, top)
            samples += page.getPixel(x, bottom)
            val y = top + (bottom - top) * i / (steps - 1)
            samples += page.getPixel(left, y)
            samples += page.getPixel(right, y)
        }
        return medianColor(samples)
    }

    fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.WHITE
        fun median(channel: (Int) -> Int): Int =
            colors.map(channel).sorted()[colors.size / 2]
        return Color.rgb(
            median { Color.red(it) },
            median { Color.green(it) },
            median { Color.blue(it) },
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** Overlay description for the reader's editable layer. */
    fun balloonsForOverlay(json: String): List<Balloon> = BalloonCodec.decode(json)

    private class PaintMeasurer : Measurer {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun width(text: String, sizePx: Float): Float {
            paint.textSize = sizePx
            return paint.measureText(text)
        }
        override fun lineHeight(sizePx: Float): Float = sizePx * 1.18f
    }
}
