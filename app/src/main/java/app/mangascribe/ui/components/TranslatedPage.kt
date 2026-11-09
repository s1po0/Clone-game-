package app.mangascribe.ui.components

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.mangascribe.core.Languages
import app.mangascribe.core.Measurer
import app.mangascribe.core.RtlTypeset
import app.mangascribe.core.Script
import app.mangascribe.core.TextFitter
import app.mangascribe.data.AppSettings
import app.mangascribe.data.Balloon
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * A translated page as an overlay: the artwork below, the balloons above it, both transformed
 * together. Tap a balloon to reveal the original lettering in that bubble, pinch to zoom, drag
 * to pan. Balloon geometry is stored in page pixels and mapped into the letterboxed view.
 */
@Composable
fun TranslatedPage(
    imagePath: String?,
    balloons: List<Balloon>,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    showOriginal: Boolean = false,
    onBalloonTap: ((Balloon) -> Unit)? = null,
) {
    // Right-to-left targets (Kurdish, Arabic, Persian) draw through the RTL path.
    val rtl = Languages.of(settings.targetLanguage).rtl
    var pageSize by remember(imagePath) { mutableStateOf(0 to 0) }
    var revealed by remember(imagePath) { mutableStateOf<Set<Int>>(emptySet()) }
    var scale by remember(imagePath) { mutableStateOf(1f) }
    var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(imagePath) {
        pageSize = withContext(Dispatchers.IO) {
            val path = imagePath?.removePrefix("file://") ?: return@withContext (0 to 0)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.outWidth to options.outHeight
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (pageSize.first == 0) {
            CircularProgressIndicator(Modifier.size(28.dp))
            return@Box
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(balloons) {
                    detectTapGestures { tap ->
                        val hit = hitTest(tap, balloons, size.width.toFloat(), size.height.toFloat(), pageSize)
                        if (hit != null) {
                            revealed = if (hit.id in revealed) revealed - hit.id else revealed + hit.id
                            onBalloonTap?.invoke(hit)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        offset = if (scale <= 1.02f) Offset.Zero else offset + pan
                    }
                },
        ) {
            if (imagePath != null) {
                AsyncImage(
                    model = imagePath,
                    contentDescription = "Comic page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                val box = fitBox(pageSize.first, pageSize.second, size.width, size.height)
                drawTranslatedBalloons(
                    balloons = balloons,
                    box = box,
                    pageWidth = pageSize.first,
                    settings = settings,
                    showOriginal = showOriginal,
                    revealed = revealed,
                    rightToLeft = rtl,
                )
            }
        }
    }
}

/** The letterboxed rectangle a Fit image occupies inside the view. */
fun fitBox(pageW: Int, pageH: Int, viewW: Float, viewH: Float): Rect {
    if (pageW <= 0 || pageH <= 0) return Rect(0f, 0f, viewW, viewH)
    val k = min(viewW / pageW, viewH / pageH)
    val w = pageW * k
    val h = pageH * k
    val left = (viewW - w) / 2f
    val top = (viewH - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/** Closest balloon whose box (grown for finger size) holds the tap, or null. */
fun hitTest(
    tap: Offset,
    balloons: List<Balloon>,
    viewW: Float,
    viewH: Float,
    pageSize: Pair<Int, Int>,
): Balloon? {
    val box = fitBox(pageSize.first, pageSize.second, viewW, viewH)
    if (box.width <= 1f) return null
    val k = box.width / pageSize.first
    var best: Balloon? = null
    var bestDistance = Float.MAX_VALUE
    for (balloon in balloons) {
        if (balloon.hidden) continue
        val r = balloon.movedRect()
        val cx = box.left + r.centerX * k
        val cy = box.top + r.centerY * k
        val rx = max(r.width * k * 0.8f, 44f)
        val ry = max(r.height * k * 0.8f, 44f)
        val dx = kotlin.math.abs(tap.x - cx)
        val dy = kotlin.math.abs(tap.y - cy)
        if (dx > rx || dy > ry) continue
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) { bestDistance = distance; best = balloon }
    }
    return best
}

/**
 * Kurdish/Arabic/Persian ink for one balloon: right-aligned lines, wrap points the Arabic
 * shaper accepts, kashida on a short single line. Kept beside the Latin path so the two can
 * be compared line for line.
 */
private fun DrawScope.drawRtlBalloonText(
    paint: Paint,
    measurer: Measurer,
    text: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    boxW: Float,
    boxH: Float,
    seed: Float,
    settings: AppSettings,
) {
    val clean = RtlTypeset.normalisePunctuation(text)
    var size = max(8f, seed)
    var lines = RtlTypeset.wrap(clean, boxW, size, measurer)
    var guard = 0
    while (guard++ < 24 && lines.size * measurer.lineHeight(size) > boxH && size > 7f) {
        size = (size * 0.92f).coerceAtLeast(7f)
        lines = RtlTypeset.wrap(clean, boxW, size, measurer)
    }
    paint.textSize = size
    paint.textAlign = Paint.Align.RIGHT
    val lineHeight = measurer.lineHeight(size)
    var y = (top + bottom) / 2f - (lines.size * lineHeight) / 2f + lineHeight * 0.78f
    // right edge of the padded text box, not of the mask
    val pad = max(1.5f, min(right - left, bottom - top) * 0.10f)
    val edge = right - pad / 2f
    for (line in lines) {
        drawContext.canvas.nativeCanvas.drawText(line, edge, y, paint)
        y += lineHeight
    }
    paint.textAlign = Paint.Align.CENTER
}

private fun DrawScope.drawTranslatedBalloons(
    balloons: List<Balloon>,
    box: Rect,
    pageWidth: Int,
    settings: AppSettings,
    showOriginal: Boolean,
    revealed: Set<Int>,
    rightToLeft: Boolean,
) {
    if (box.width <= 1f || pageWidth <= 0) return
    val k = box.width / pageWidth
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        color = android.graphics.Color.BLACK
    }
    val measurer: Measurer = object : Measurer {
        override fun width(text: String, sizePx: Float): Float {
            paint.textSize = sizePx
            return paint.measureText(text)
        }
        override fun lineHeight(sizePx: Float): Float = sizePx * 1.16f
    }

    for (balloon in balloons) {
        if (balloon.hidden) continue
        val showSource = showOriginal || balloon.id in revealed
        val text = if (showSource) balloon.sourceText else balloon.translatedText
        if (text.isBlank()) continue
        val r = balloon.movedRect()
        val left = box.left + r.left * k
        val top = box.top + r.top * k
        val right = box.left + r.right * k
        val bottom = box.top + r.bottom * k
        if (right - left < 3f || bottom - top < 3f) continue
        val pad = max(1.5f, min(right - left, bottom - top) * 0.10f)
        rotate(
            degrees = balloon.angleDegrees.coerceIn(-30f, 30f),
            pivot = Offset((left + right) / 2f, (top + bottom) / 2f),
        ) {
            if (!showSource && balloon.maskOriginal && settings.maskOriginal) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    topLeft = Offset(left - pad, top - pad),
                    size = Size(right - left + pad * 2, bottom - top + pad * 2),
                    cornerRadius = CornerRadius(min(right - left, bottom - top) * 0.28f),
                )
            }
            val boxW = (right - left) - pad
            val boxH = (bottom - top) - pad
            if (boxW <= 4f || boxH <= 4f) return@rotate
            val seed = max(8f, min(boxH, boxW * 1.1f) * 0.92f * settings.fontScale)
            if (rightToLeft && !showSource) {
                drawRtlBalloonText(paint, measurer, text, left, top, right, bottom, boxW, boxH, seed, settings)
                return@rotate
            }
            val fitted = TextFitter.fit(
                text = text,
                boxWidth = boxW,
                boxHeight = boxH,
                startSizePx = seed,
                measurer = measurer,
                minSizePx = if (boxH < 40f) 7f else 9f,
            )
            paint.textSize = fitted.fontSize
            var y = (top + bottom) / 2f - (fitted.lines.size * fitted.lineHeight) / 2f +
                fitted.lineHeight * 0.78f
            for (line in fitted.lines) {
                if (settings.outlineText) {
                    val stroke = Paint(paint).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = max(1.5f, paint.textSize * 0.12f)
                        color = android.graphics.Color.WHITE
                    }
                    drawContext.canvas.nativeCanvas.drawText(line, (left + right) / 2f, y, stroke)
                }
                drawContext.canvas.nativeCanvas.drawText(line, (left + right) / 2f, y, paint)
                y += fitted.lineHeight
            }
        }
    }
}
