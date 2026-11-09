package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A monospace measurer: 0.6 em per character, 1.2 em line height. Deterministic on the JVM. */
private val mono = object : Measurer {
    override fun width(text: String, sizePx: Float): Float = text.length * sizePx * 0.6f
    override fun lineHeight(sizePx: Float): Float = sizePx * 1.2f
}

class TextFitterTest {

    @Test
    fun `wraps latin text at word boundaries`() {
        val lines = TextFitter.wrap("the quick brown fox jumps", 6 * 10f * 0.6f, 10f, mono)
        assertTrue(lines.size > 1)
        assertTrue(lines.none { it.length > 6 })
    }

    @Test
    fun `wraps cjk without spaces`() {
        val lines = TextFitter.wrap("おまえほんとうに行くのか", 5 * 20f * 0.6f, 20f, mono)
        assertTrue(lines.size >= 2)
        assertTrue(lines.all { it.length <= 5 })
    }

    @Test
    fun `shrinks the font until the block fits its box`() {
        val result = TextFitter.fit(
            text = "a fairly long line of translated dialogue for one small balloon",
            boxWidth = 120f,
            boxHeight = 60f,
            startSizePx = 40f,
            measurer = mono,
        )
        assertFalse(result.overflows)
        assertTrue(result.blockHeight <= 60.5f)
        assertTrue(result.fontSize < 40f)
    }

    @Test
    fun `keeps a short line at the starting size`() {
        val result = TextFitter.fit("Hi", 200f, 60f, 32f, mono)
        assertEquals(32f, result.fontSize, 0.01f)
        assertEquals(listOf("Hi"), result.lines)
    }

    @Test
    fun `empty text fits to nothing`() {
        val result = TextFitter.fit("   ", 100f, 40f, 20f, mono)
        assertTrue(result.lines.isEmpty())
        assertFalse(result.overflows)
    }

    @Test
    fun `a token wider than the box is hard-broken`() {
        val lines = TextFitter.wrap("supercalifragilistic", 10 * 10f * 0.6f, 10f, mono)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length <= 10 })
    }

    @Test
    fun `tokenizer splits cjk per character`() {
        val tokens = TextFitter.tokenize("あい u")
        assertTrue(tokens.contains("あ"))
        assertTrue(tokens.any { it.contains("u") })
    }

    @Test
    fun `vertical layout fills columns right to left`() {
        val columns = VerticalTypeset.layout("あいうえおかきくけこ", 3 * 20f, 4 * 20f, 20f)
        assertTrue(columns.size >= 2)
        assertTrue(columns[0].x > columns[1].x)
        assertTrue(columns.all { it.text.length <= 4 })
    }
}
