package app.mangascribe.ocr

import android.content.Context
import android.graphics.Bitmap
import app.mangascribe.data.PageImages
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
    /**
     * Walk a very tall strip in bands instead of decoding it whole. A 12000 px webtoon page
     * at OCR resolution is 200+ MB as one bitmap; banding keeps each pass inside a phone's
     * memory and is why long strips used to fail with a decode error.
     */
    val bandHeightPx: Int = 1600,
    val bandOverlapPx: Int = 160,
)

data class OcrResult(
    val blocks: List<TextBlock>,
    val detectedLanguage: String?,
    val modelsUsed: List<String>,
    val elapsedMs: Long,
)

/** Where a page comes from, so OCR can choose whole-image or banded reading. */
sealed class ImageSource {
    /** Page width and height in page pixels, before any sampling. */
    abstract val width: Int
    abstract val height: Int
    abstract suspend fun load(upscaleBelowWidth: Int): Bitmap?

    class BitmapSource(private val source: Bitmap) : ImageSource() {
        override val width: Int get() = source.width
        override val height: Int get() = source.height
        override suspend fun load(upscaleBelowWidth: Int): Bitmap = source
    }

    class FileSource(
        val context: Context,
        val uriString: String,
        override val width: Int,
        override val height: Int,
    ) : ImageSource() {
        override suspend fun load(upscaleBelowWidth: Int): Bitmap? =
            PageImages.decode(context, uriString, PageImages.OCR_TARGET_WIDTH)
    }
}

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
    ): OcrResult = recognizeSource(ImageSource.BitmapSource(bitmap), languageCode, options)

    /**
     * Read a page from anywhere: an already-decoded bitmap, or an on-disk image that can be
     * cropped in bands. The band walk is what lets a very long strip be recognised at all:
     * decoding a 12000 px webtoon page whole, at OCR resolution, is an out-of-memory crash on
     * a phone — which used to surface as "the page image could not be read".
     */
    suspend fun recognizeSource(
        source: ImageSource,
        languageCode: String,
        options: OcrOptions = OcrOptions(),
    ): OcrResult {
        val started = System.currentTimeMillis()
        val models = Languages.ocrModelsFor(languageCode)
        val collected = mutableListOf<TextBlock>()

        val banding = source is ImageSource.FileSource &&
            PageImages.needsBanding(source.width, source.height)

        if (!banding) {
            val bitmap = source.load(options.upscaleBelowWidth)
                ?: throw PageImages.UnreadablePageException(
                    "The page image could not be read for text recognition."
                )
            val scale = if (bitmap.width > 0) source.width.toFloat() / bitmap.width else 1f
            for (model in models) {
                collected += recognizeWithModel(bitmap, model).map { it.scaleBack(1f / scale) }
                if (model != "latin" && collected.size > 24) break
            }
            return finish(collected, languageCode, options, models, started)
        }

        val fileSource = source as ImageSource.FileSource
        var top = 0
        val step = (options.bandHeightPx - options.bandOverlapPx).coerceAtLeast(400)
        var guard = 0
        while (top < fileSource.height && guard++ < 120) {
            val band = PageImages.decodeBand(
                fileSource.context,
                fileSource.uriString,
                top,
                options.bandHeightPx,
                PageImages.OCR_TARGET_WIDTH,
            ) ?: break
            // A band is in page pixels already; decodeBand samples by the same factor the
            // whole page would have used, so coordinates only need the band's own offset.
            for (model in models) {
                val found = recognizeWithModel(band, model)
                collected += found.map { block ->
                    block.copy(
                        polygon = block.polygon.map { PointF(it.x, it.y + top) },
                    )
                }
                if (model != "latin" && collected.size > 24) break
            }
            band.recycle()
            top += step
            if (top + options.bandOverlapPx >= fileSource.height) break
        }
        return finish(collected, languageCode, options, models, started)
    }

    /** Everything after recognition: language, junk, merging, cleaning. Shared by both paths. */
    private fun finish(
        raw: List<TextBlock>,
        languageCode: String,
        options: OcrOptions,
        models: List<String>,
        started: Long,
    ): OcrResult {
        val joined = raw.joinToString(" ") { it.text }
        val detected = Languages.detect(joined)
        var filtered = raw
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
            val cleaned = OcrCleanup.clean(block.text, detected ?: languageCode, options.stripFurigana)
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
