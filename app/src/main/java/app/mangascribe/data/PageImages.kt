package app.mangascribe.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Page loading. A 3000 px webtoon strip decoded at full size is an instant OOM, so the decoder
 * samples down to the width the screen can show (plus headroom for OCR) and keeps the aspect
 * ratio the reader's scroll views depend on.
 */
object PageImages {

    const val OCR_TARGET_WIDTH = 1400
    const val READER_TARGET_WIDTH = 1600

    suspend fun decode(
        context: Context,
        uriString: String,
        targetWidth: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.parse(uriString)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            } ?: return@runCatching null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val sample = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            if (bitmap == null) return@runCatching null

            // exact resize after sampling: inSampleSize only halves, this lands on target
            if (bitmap.width != targetWidth && targetWidth > 0) {
                val h = (bitmap.height.toFloat() / bitmap.width * targetWidth).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, h, true)
                if (scaled !== bitmap) bitmap.recycle()
                bitmap = scaled
            }
            bitmap
        }.getOrNull()
    }

    suspend fun dimensions(context: Context, uriString: String): Pair<Int, Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
                options.outWidth to options.outHeight
            }.getOrDefault(0 to 0)
        }

    /** Powers of two only: anything else wastes memory in BitmapFactory. */
    fun sampleSize(width: Int, height: Int, targetWidth: Int): Int {
        if (targetWidth <= 0) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= targetWidth && h / 2 >= targetWidth / 3) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
