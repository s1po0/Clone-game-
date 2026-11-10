package app.mangascribe.core

import app.mangascribe.ocr.OcrQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Choosing between two readings of the same page. On a Kurdish page the Arabic-script reading
 * always wins over a Latin one, several short bubbles beat one long misread, and junk does not
 * earn points just for existing.
 */
class OcrQualityTest {

    private fun block(text: String) = TextBlock(
        text = text,
        polygon = listOf(PointF(0f, 0f), PointF(100f, 0f), PointF(100f, 40f), PointF(0f, 40f)),
    )

    @Test
    fun `the kurdish reading beats a latin one on the same page`() {
        val kurdish = listOf(block("من تۆ دەپارێزم!"), block("ڕابە، دێوەکان دێن!"))
        val latin = listOf(block("wxz qqq"), block("IIII III"))
        assertTrue(
            OcrQuality.scoreBlocks(kurdish, "ckb") > OcrQuality.scoreBlocks(latin, "ckb"),
        )
    }

    @Test
    fun `several short bubbles beat one long misread`() {
        val many = listOf(block("چی؟!"), block("ڕابە!"), block("بەڵێ."), block("نا!"))
        val one = listOf(block("چیڕابەبەڵێنا"))
        assertTrue(OcrQuality.scoreBlocks(many, "ckb") > OcrQuality.scoreBlocks(one, "ckb"))
    }

    @Test
    fun `junk does not earn points`() {
        val junk = listOf(block("PY"), block("SFX"), block("??"))
        assertEquals(0.0, OcrQuality.scoreBlocks(junk, "ckb"), 0.6)
    }

    @Test
    fun `an empty reading scores zero`() {
        assertEquals(0.0, OcrQuality.scoreBlocks(emptyList(), "ckb"), 0.0)
    }

    @Test
    fun `japanese is scored on its own script`() {
        val japanese = listOf(block("待って！"))
        val latin = listOf(block("Matte!"))
        assertTrue(
            OcrQuality.scoreBlocks(japanese, "ja") > OcrQuality.scoreBlocks(latin, "ja") * 0.9,
        )
    }
}
