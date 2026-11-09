package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OcrCleanupTest {

    @Test
    fun `strips furigana between kanji groups`() {
        assertEquals("皇帝陛下", OcrCleanup.stripFurigana("皇帝こうてい陛下"))
    }

    @Test
    fun `keeps ordinary japanese sentences`() {
        val sentence = "おまえ本当に行くのか"
        assertEquals(sentence, OcrCleanup.stripFurigana(sentence))
    }

    @Test
    fun `repairs words split across lines`() {
        assertEquals(
            "translation of the page",
            OcrCleanup.repairLineBreaks("trans-\nlation  of the\n page"),
        )
    }

    @Test
    fun `junk detection drops page numbers and punctuation`() {
        assertTrue(OcrCleanup.isJunk("12"))
        assertTrue(OcrCleanup.isJunk("..."))
        assertTrue(OcrCleanup.isJunk("  "))
        assertFalse(OcrCleanup.isJunk("Wait!"))
        assertFalse(OcrCleanup.isJunk("待って"))
    }

    @Test
    fun `clean trims noise glyphs`() {
        assertEquals("hello", OcrCleanup.clean("| hello ©", "en", stripFurigana = true))
    }

    @Test
    fun `detect picks japanese over latin when kana present`() {
        assertEquals("ja", Languages.detect("おまえ 123"))
        assertEquals("ko", Languages.detect("안녕하세요"))
        assertEquals("zh-Hans", Languages.detect("你好世界"))
        assertEquals("en", Languages.detect("just english"))
        assertEquals("ar", Languages.detect("کوردی"))
    }

    @Test
    fun `ocr models include latin companion for cjk`() {
        val models = Languages.ocrModelsFor("ja")
        assertTrue(models.contains("japanese"))
        assertTrue(models.contains("latin"))
        assertEquals(listOf("latin"), Languages.ocrModelsFor("en"))
    }
}

class BlockMergerTest {

    private fun block(text: String, l: Float, t: Float, r: Float, b: Float, angle: Float = 0f) =
        TextBlock(
            text = text,
            polygon = listOf(
                PointF(l, t), PointF(r, t), PointF(r, b), PointF(l, b)
            ),
            angleDegrees = angle,
        )

    @Test
    fun `two stacked lines in one balloon merge into one block`() {
        val a = block("ああ。", 100f, 100f, 220f, 140f)
        val b = block("決めたことだ。", 100f, 146f, 260f, 186f)
        val merged = BlockMerger.merge(listOf(a, b), BlockMerger.MergeOptions("ja"))
        assertEquals(1, merged.size)
        assertEquals("ああ。\n決めたことだ。", merged[0].text)
    }

    @Test
    fun `lines far apart stay separate`() {
        val a = block("first", 10f, 10f, 200f, 50f)
        val b = block("second", 10f, 400f, 200f, 440f)
        val merged = BlockMerger.merge(listOf(a, b), BlockMerger.MergeOptions("en"))
        assertEquals(2, merged.size)
    }

    @Test
    fun `japanese columns read right to left, latin lines top to bottom`() {
        val left = block("二", 100f, 100f, 140f, 300f)
        val right = block("一", 160f, 100f, 200f, 300f)
        // manga columns run right to left: the rightmost column is read first
        val orderedJa = BlockMerger.orderForReading(listOf(left, right), "ja")
        assertEquals("一", orderedJa[0].text)

        val top = block("top", 100f, 100f, 300f, 140f)
        val bottom = block("bottom", 100f, 160f, 300f, 200f)
        val orderedEn = BlockMerger.orderForReading(listOf(bottom, top), "en")
        assertEquals("top", orderedEn[0].text)
    }

    @Test
    fun `arabic reads right to left`() {
        // deliberate: same rule, different script
        val left = block("ب", 100f, 100f, 140f, 300f)
        val right = block("أ", 200f, 100f, 240f, 300f)
        val ordered = BlockMerger.orderForReading(listOf(left, right), "ar")
        assertEquals("أ", ordered[0].text)
    }
}

class GeometryTest {

    @Test
    fun `angle of a level outline is zero`() {
        val points = listOf(PointF(0f, 0f), PointF(100f, 0f), PointF(100f, 40f), PointF(0f, 40f))
        assertTrue(abs(Geometry.angleOf(points)) < 0.01f)
    }

    @Test
    fun `angle of a tilted outline is measured clockwise`() {
        val points = listOf(PointF(0f, 0f), PointF(100f, 17.6f), PointF(90f, 60f), PointF(-10f, 42f))
        assertEquals(10f, Geometry.angleOf(points), 0.5f)
    }

    @Test
    fun `vertical columns are recognised from the box shape`() {
        val block = TextBlock(
            text = "たてがき",
            polygon = listOf(PointF(0f, 0f), PointF(40f, 0f), PointF(40f, 200f), PointF(0f, 200f)),
        )
        assertTrue(block.vertical)
        assertEquals(TextAngle.DOWN_RIGHT, block.copy(angleDegrees = 20f).textAngle)
    }

    @Test
    fun `overlap ratio uses the smaller box`() {
        val a = RectF(0f, 0f, 100f, 100f)
        val b = RectF(50f, 50f, 150f, 150f)
        assertEquals(0.25f, a.overlapRatio(b), 0.01f)
    }
}
