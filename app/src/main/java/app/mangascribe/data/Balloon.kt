package app.mangascribe.data

import app.mangascribe.core.PointF
import app.mangascribe.core.TextBlock
import org.json.JSONArray
import org.json.JSONObject

/**
 * A balloon after OCR and translation: geometry + both texts + what the reader may edit.
 * Kept as JSON in the page row, so the reader can redraw a translated page offline forever.
 */
data class Balloon(
    val id: Int,
    val rect: app.mangascribe.core.RectF,
    val sourceText: String,
    var translatedText: String,
    val angleDegrees: Float,
    val vertical: Boolean,
    /** nudges applied in Edit Mode, in page pixels */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val hidden: Boolean = false,
    /** mask the original lettering with a fill in the page's paper colour */
    val maskOriginal: Boolean = true,
) {
    fun movedRect() = app.mangascribe.core.RectF(
        rect.left + offsetX,
        rect.top + offsetY,
        rect.right + offsetX,
        rect.bottom + offsetY,
    )

    companion object {
        fun from(block: TextBlock, translated: String) = Balloon(
            id = block.id,
            rect = block.bounds,
            sourceText = block.text,
            translatedText = translated,
            angleDegrees = block.angleDegrees,
            vertical = block.vertical,
        )
    }
}

object BalloonCodec {
    fun encode(balloons: List<Balloon>): String {
        val array = JSONArray()
        balloons.forEach { b ->
            array.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("l", b.rect.left.toDouble())
                    put("t", b.rect.top.toDouble())
                    put("r", b.rect.right.toDouble())
                    put("b", b.rect.bottom.toDouble())
                    put("src", b.sourceText)
                    put("tr", b.translatedText)
                    put("angle", b.angleDegrees.toDouble())
                    put("v", b.vertical)
                    put("dx", b.offsetX.toDouble())
                    put("dy", b.offsetY.toDouble())
                    put("s", b.scale.toDouble())
                    put("hidden", b.hidden)
                    put("mask", b.maskOriginal)
                }
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<Balloon> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Balloon(
                    id = o.optInt("id", i),
                    rect = app.mangascribe.core.RectF(
                        o.optDouble("l", 0.0).toFloat(),
                        o.optDouble("t", 0.0).toFloat(),
                        o.optDouble("r", 0.0).toFloat(),
                        o.optDouble("b", 0.0).toFloat(),
                    ),
                    sourceText = o.optString("src"),
                    translatedText = o.optString("tr"),
                    angleDegrees = o.optDouble("angle", 0.0).toFloat(),
                    vertical = o.optBoolean("v", false),
                    offsetX = o.optDouble("dx", 0.0).toFloat(),
                    offsetY = o.optDouble("dy", 0.0).toFloat(),
                    scale = o.optDouble("s", 1.0).toFloat(),
                    hidden = o.optBoolean("hidden", false),
                    maskOriginal = o.optBoolean("mask", true),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** Convert ML Kit outlines into the app's block model. Kept here so OCR code stays thin. */
object BlockCodec {
    fun from(points: List<android.graphics.Point>, text: String, languageTag: String?, angle: Float): TextBlock =
        TextBlock(
            text = text,
            polygon = points.map { PointF(it.x.toFloat(), it.y.toFloat()) },
            languageTag = languageTag,
            angleDegrees = angle,
        )
}
