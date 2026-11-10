package app.mangascribe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Page loading. Two things go wrong with comic pages and both are handled here rather than
 * being reported as "the page image could not be read":
 *
 *  - **Size.** A 3000 px webtoon strip decoded at full size is an instant OOM on a phone, so
 *    the decoder samples down to what the screen (or the OCR pass) needs.
 *  - **Reachability.** A `content://` URI from the share sheet can be revoked the moment the
 *    activity that granted it goes away, and a `file://` URI from a cleaned cache is simply
 *    gone. Both cases are detected when the page is imported (a copy is made then) and
 *    reported here as a specific reason, never as a silent null.
 */
object PageImages {

    const val OCR_TARGET_WIDTH = 1400
    const val READER_TARGET_WIDTH = 1600

    /** What went wrong, in words a reader can act on. */
    class UnreadablePageException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Decode a page at roughly [targetWidth]. Throws [UnreadablePageException] with the reason
     * instead of returning null, so the page row shows something truer than "could not read".
     */
    suspend fun decodeOrThrow(
        context: Context,
        uriString: String,
        targetWidth: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        decodeInternal(context, uriString, targetWidth).getOrElse { error ->
            throw when (error) {
                is UnreadablePageException -> error
                is OutOfMemoryError -> UnreadablePageException(
                    "The page is too large for this phone's memory. Try a smaller scan, or " +
                        "import the chapter as .cbz pages.", error,
                )
                else -> UnreadablePageException(
                    "The page image could not be read: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
        }
    }

    suspend fun decode(context: Context, uriString: String, targetWidth: Int): Bitmap? =
        withContext(Dispatchers.IO) { decodeInternal(context, uriString, targetWidth).getOrNull() }

    private fun decodeInternal(context: Context, uriString: String, targetWidth: Int): Result<Bitmap> =
        runCatching {
            val uri = Uri.parse(uriString)
            // A local file that no longer exists is the most common case after a cache clean.
            if (uri.scheme == "file") {
                val path = uri.path ?: throw UnreadablePageException("The page has no file path")
                val file = File(path)
                if (!file.exists()) {
                    throw UnreadablePageException(
                        "This page's file is gone (it was in a temporary folder that Android " +
                            "cleared). Import the chapter again."
                    )
                }
                if (file.length() == 0L) {
                    throw UnreadablePageException("This page's file is empty (0 bytes).")
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // Decoding bounds returns null by design; what matters is outWidth/outHeight after.
            openStream(context, uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw UnreadablePageException(
                    "This file is not an image this phone can decode " +
                        "(JPEG, PNG and WebP are supported). It may be a renamed file."
                )
            }

            val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            var bitmap = openStream(context, uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: throw UnreadablePageException(
                "The image decoded as empty — the file may be truncated. Re-import it."
            )

            // Exact resize after sampling: inSampleSize only halves, so this lands on target
            // without ever producing a 1-px-wide bitmap (the old bug for very tall strips).
            if (targetWidth > 0 && bitmap.width != targetWidth) {
                val w = min(targetWidth, bitmap.width)
                if (w >= 1) {
                    val h = (bitmap.height.toFloat() / bitmap.width * w).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
                    if (scaled !== bitmap) bitmap.recycle()
                    bitmap = scaled
                }
            }
            bitmap
        }

    private fun openStream(context: Context, uri: Uri) =
        context.contentResolver.openInputStream(uri)
            ?: throw UnreadablePageException(
                "This page cannot be opened any more: the app that shared it took back " +
                    "permission. Import it again from the gallery."
            )

    suspend fun dimensions(context: Context, uriString: String): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(context, Uri.parse(uriString)).use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                options.outWidth to options.outHeight
            }.getOrDefault(0 to 0)
        }

    /**
     * Crop a band of a very tall strip without decoding the whole thing. A 12000 px webtoon
     * page cannot be OCR'd as one bitmap at readable resolution; the caller walks the strip in
     * overlapping bands and offsets the results. Falls back to a scaled whole-page decode when
     * the platform cannot region-decode the format.
     */
    suspend fun decodeBand(
        context: Context,
        uriString: String,
        top: Int,
        height: Int,
        targetWidth: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val decoder = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                runCatching {
                    BitmapRegionDecoder.newInstance(pfd.fileDescriptor, false)
                }.getOrNull()
            }
            if (decoder != null) {
                val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val rect = android.graphics.Rect(
                    0,
                    top.coerceIn(0, bounds.outHeight - 1),
                    bounds.outWidth,
                    (top + height).coerceIn(1, bounds.outHeight),
                )
                val band = decoder.decodeRegion(rect, options)
                decoder.recycle()
                band
            } else {
                // Region decoding not available for this format: take the scaled page and crop.
                val page = decodeInternal(context, uriString, targetWidth).getOrNull() ?: return@runCatching null
                val scale = page.width.toFloat() / bounds.outWidth
                val y = (top * scale).toInt().coerceIn(0, page.height - 1)
                val h = (height * scale).toInt().coerceIn(1, page.height - y)
                val cropped = Bitmap.createBitmap(page, 0, y, page.width, h)
                if (cropped !== page) page.recycle()
                cropped
            }
        }.getOrNull()
    }

    /** Powers of two only: anything else wastes memory in BitmapFactory. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int): Int {
        if (targetWidth <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        // Guard the strip case: do not halve a 900 px wide image just because it is 12000 tall.
        while (w / 2 >= targetWidth && h / 2 >= 400) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /** True when OCR should walk the page in bands rather than read it whole. */
    fun needsBanding(width: Int, height: Int, targetWidth: Int = OCR_TARGET_WIDTH): Boolean {
        if (width <= 0) return false
        val scaledHeight = height * (targetWidth.toFloat() / width)
        return scaledHeight > 3200f
    }
}
