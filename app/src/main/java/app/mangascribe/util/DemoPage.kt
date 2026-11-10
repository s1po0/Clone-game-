package app.mangascribe.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.io.File
import java.io.FileOutputStream

/**
 * Draws the sample page shipped with the app: a two-panel manga page with Japanese dialogue
 * and a couple of sound effects. It exists so a new install can run OCR → translate → typeset
 * without importing anything, and it is drawn in code rather than bundled as artwork.
 */
object DemoPage {

    private const val W = 900
    private const val H = 1300

    fun writeSamplePage(context: Context): File {
        val dir = File(context.filesDir, "demo").apply { mkdirs() }
        val out = File(dir, "sample-page.png")
        if (out.exists() && out.length() > 0) return out
        // A zero-length leftover (a crash mid-write on a previous run) is rebuilt, not reused.
        if (out.exists()) out.delete()
        val bitmap = draw()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return out
    }

    fun draw(): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#F6F1E7"))

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14161B")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        // panel 1: a close-up, sky and a rooftop
        canvas.drawRect(40f, 40f, W - 40f, 700f, fill)
        canvas.save()
        canvas.clipRect(46f, 46f, W - 46f, 694f)
        canvas.drawRect(46f, 46f, W - 46f, 420f, Paint().apply { color = Color.parseColor("#BFD8E8") })
        // speed lines
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7C8B99"); strokeWidth = 3f
        }
        for (i in 0..14) {
            canvas.drawLine(46f, 430f + i * 14f, W - 46f, 400f + i * 18f, line)
        }
        // rooftop silhouette
        val roof = Path().apply {
            moveTo(46f, 690f); lineTo(46f, 600f); lineTo(240f, 540f); lineTo(430f, 610f)
            lineTo(620f, 520f); lineTo(W - 46f, 590f); lineTo(W - 46f, 690f); close()
        }
        canvas.drawPath(roof, Paint().apply { color = Color.parseColor("#2C3440") })
        canvas.restore()
        canvas.drawRect(40f, 40f, W - 40f, 700f, ink)

        // panel 2: two figures facing each other
        canvas.drawRect(40f, 730f, W - 40f, H - 40f, fill)
        canvas.save()
        canvas.clipRect(46f, 736f, W - 46f, H - 46f)
        canvas.drawRect(46f, 736f, W - 46f, H - 46f, Paint().apply { color = Color.parseColor("#E8E1D2") })
        drawFigure(canvas, 250f, 1060f, 150f, Color.parseColor("#39404C"))
        drawFigure(canvas, 660f, 1040f, 165f, Color.parseColor("#7A2E2E"))
        canvas.restore()
        canvas.drawRect(40f, 730f, W - 40f, H - 40f, ink)

        // speech balloons with Japanese lettering (drawn by the platform font)
        drawBalloon(canvas, 120f, 120f, 360f, 200f, "おまえ、\nほんとうに\n行くのか？")
        drawBalloon(canvas, 520f, 330f, 300f, 210f, "ああ。\n決めた\nことだ。")
        drawBalloon(canvas, 150f, 820f, 300f, 190f, "待って\nくれよ！")
        // sound effect, tilted
        val sfx = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14161B")
            textSize = 64f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        canvas.save()
        canvas.rotate(-14f, 470f, 1180f)
        canvas.drawText("ドン", 420f, 1200f, sfx)
        canvas.restore()
        return bitmap
    }

    private fun drawFigure(canvas: Canvas, cx: Float, baseY: Float, height: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val body = Path().apply {
            moveTo(cx - height * 0.30f, baseY)
            lineTo(cx - height * 0.24f, baseY - height * 0.55f)
            lineTo(cx - height * 0.12f, baseY - height * 0.72f)
            lineTo(cx - height * 0.10f, baseY - height * 0.92f)
            lineTo(cx + height * 0.10f, baseY - height * 0.92f)
            lineTo(cx + height * 0.12f, baseY - height * 0.72f)
            lineTo(cx + height * 0.24f, baseY - height * 0.55f)
            lineTo(cx + height * 0.30f, baseY)
            close()
        }
        canvas.drawPath(body, paint)
        canvas.drawCircle(cx, baseY - height * 1.0f, height * 0.13f, paint)
        // hair: a smaller dark arc, so the silhouette reads as a person and not a pin
        canvas.drawArc(
            cx - height * 0.14f, baseY - height * 1.14f,
            cx + height * 0.14f, baseY - height * 0.90f,
            190f, 160f, true,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.parseColor("#14161B") },
        )
    }

    private fun drawBalloon(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, text: String) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14161B"); style = Paint.Style.STROKE; strokeWidth = 5f
        }
        canvas.drawOval(x, y, x + w, y + h, fill)
        canvas.drawOval(x, y, x + w, y + h, stroke)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#14161B")
            textSize = 40f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val lines = text.split("\n")
        val startY = y + h / 2f - (lines.size - 1) * 26f
        lines.forEachIndexed { i, l -> canvas.drawText(l, x + w / 2f, startY + i * 52f, paint) }
    }
}
