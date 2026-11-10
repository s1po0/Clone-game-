package app.mangascribe.ocr

import android.content.Context
import android.graphics.Bitmap
import app.mangascribe.core.BlockMerger
import app.mangascribe.core.Geometry
import app.mangascribe.core.Languages
import app.mangascribe.core.OcrCleanup
import app.mangascribe.core.PointF
import app.mangascribe.core.RectF
import app.mangascribe.core.TextBlock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrOptions(
    /** Strip furigana runs before translation — on for Japanese by default. */
    val stripFurigana: Boolean = true,
    /** Join neighbouring text lines that belong to one balloon. */
    val mergeBlocks: Boolean = true,
    /** Drop page numbers and punctuation-only detections. */
    val dropJunk: Boolean = true,
    /** Upscale small pages before recognition: ML Kit reads 10 px lettering poorly. */
    val upscaleBelowWidth: Int = 1100,
    /** Ignore detections smaller than this many pixels of height (scan noise, dust). */
    val minBlockHeightPx: Float = 6f,
)

data class OcrResult(
    val blocks: List<TextBlock>,
    val detectedLanguage: String?,
    val modelsUsed: List<String>,
    val elapsedMs: Long,
)

interface OcrEngine {
    suspend fun recognize(
        bitmap: Bitmap,
        languageCode: String,
        options: OcrOptions = OcrOptions(),
    ): OcrResult
    fun close()
}

/**
 * On-device OCR through ML Kit's bundled script models. Japanese, Chinese, Korean and
 * Devanagari each get their own recogniser; Latin covers English and everything else.
 * Recognition runs twice when a page mixes scripts (manga: Japanese columns plus Latin sfx)
 * and the two result sets are merged by balloon.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {

    private val recognizers = mutableMapOf<String, TextRecognizer>()

    private fun recognizerFor(model: String): TextRecognizer = recognizers.getOrPut(model) {
        when (model) {
            "japanese" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "chinese" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            "korean" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "devanagari" -> TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        languageCode: String,
        options: OcrOptions,
    ): OcrResult {
        val started = System.currentTimeMillis()
        val prepared = prepare(bitmap, options.upscaleBelowWidth)
        val scale = prepared.second
        val models = Languages.ocrModelsFor(languageCode)
        val collected = mutableListOf<TextBlock>()

        for (model in models) {
            val blocks = recognizeWithModel(prepared.first, model)
            collected += blocks.map { it.scaleBack(scale) }
            // A script model that already found plenty of text does not need the Latin pass
            // to add noise on top; the Latin pass is for sfx and signage.
            if (model != "latin" && collected.size > 24) break
        }

        val joined = collected.joinToString(" ") { it.text }
        val detected = Languages.detect(joined)

        var filtered = collected
            .filter { !options.dropJunk || !OcrCleanup.isJunk(it.text) }
            .filter { it.bounds.height >= options.minBlockHeightPx &&
                it.bounds.width >= options.minBlockHeightPx }

        if (options.mergeBlocks) {
            filtered = BlockMerger.merge(
                filtered,
                BlockMerger.MergeOptions(languageCode = detected ?: languageCode),
            )
        }

        filtered = filtered.mapNotNull { block ->
            val cleaned = OcrCleanup.clean(
                block.text,
                detected ?: languageCode,
                options.stripFurigana,
            )
            if (cleaned.isBlank() || (options.dropJunk && OcrCleanup.isJunk(cleaned))) null
            else block.copy(text = cleaned)
        }.mapIndexed { index, block -> block.copy(id = index) }

        return OcrResult(
            blocks = filtered,
            detectedLanguage = detected,
            modelsUsed = models,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    private fun TextBlock.scaleBack(scale: Float): TextBlock {
        if (scale == 1f) return this
        return copy(polygon = polygon.map { PointF(it.x / scale, it.y / scale) })
    }

    private suspend fun recognizeWithModel(bitmap: Bitmap, model: String): List<TextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = recognizerFor(model)
        val text = suspendCancellableCoroutine<com.google.mlkit.vision.text.Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val blocks = mutableListOf<TextBlock>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val corners = line.cornerPoints
                val box = line.boundingBox
                val polygon = when {
                    corners != null && corners.isNotEmpty() ->
                        corners.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                    box != null -> listOf(
                        PointF(box.left.toFloat(), box.top.toFloat()),
                        PointF(box.right.toFloat(), box.top.toFloat()),
                        PointF(box.right.toFloat(), box.bottom.toFloat()),
                        PointF(box.left.toFloat(), box.bottom.toFloat()),
                    )
                    else -> continue // a line with neither corners nor a box cannot be drawn
                }
                val angle = Geometry.angleOf(polygon)
                blocks += TextBlock(
                    text = line.text,
                    polygon = polygon,
                    languageTag = line.recognizedLanguage,
                    angleDegrees = angle,
                )
            }
        }
        return blocks
    }

    /**
     * Returns the bitmap to read and the factor it was scaled by. Upscaling a low-resolution
     * scan is the single largest OCR accuracy win on phone screenshots of manga pages.
     */
    private fun prepare(bitmap: Bitmap, upscaleBelowWidth: Int): Pair<Bitmap, Float> {
        if (bitmap.width >= upscaleBelowWidth) return bitmap to 1f
        val factor = (upscaleBelowWidth.toFloat() / bitmap.width).coerceAtMost(2.5f)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * factor).toInt(),
            (bitmap.height * factor).toInt(),
            true,
        )
        return scaled to factor
    }

    override fun close() {
        recognizers.values.forEach { it.close() }
        recognizers.clear()
    }
}

/** Screen-crop helper: balloons partly outside the visible area are clamped, not dropped. */
object BlockSanitizer {
    fun clamp(blocks: List<TextBlock>, width: Int, height: Int): List<TextBlock> =
        blocks.mapNotNull { block ->
            val r: RectF = block.bounds
            val clamped = r.clampTo(width.toFloat(), height.toFloat())
            if (clamped.width < 2f || clamped.height < 2f) null
            else block.copy(
                polygon = listOf(
                    PointF(clamped.left, clamped.top),
                    PointF(clamped.right, clamped.top),
                    PointF(clamped.right, clamped.bottom),
                    PointF(clamped.left, clamped.bottom),
                )
            )
        }
}
