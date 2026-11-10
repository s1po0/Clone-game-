package app.mangascribe.core

import app.mangascribe.translate.PackedLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The working Sorani script sends 1200 characters per request instead of one request per bubble.
 * Packing is where a page can go wrong quietly: a mismatched split would put one character's
 * words into another's bubble, so a bad answer must be refused, not guessed at.
 */
class PackedLinesTest {

    @Test
    fun `a short page becomes one request`() {
        val texts = listOf("hello", "who are you?", "run!")
        val packed = PackedLines.pack(texts)
        assertEquals(1, packed.requests.size)
        assertEquals(listOf(0, 1, 2), packed.indexGroups.single())
    }

    @Test
    fun `a long page is split near twelve hundred characters`() {
        val texts = (1..40).map { "line number $it with some words in it" }
        val packed = PackedLines.pack(texts)
        assertTrue(packed.requests.size > 1)
        assertTrue(packed.requests.all { it.length <= PackedLines.MAX_CHARS + 200 })
        assertEquals(texts.indices.toList(), packed.indexGroups.flatten())
    }

    @Test
    fun `a matching answer is placed back line for line`() {
        val texts = listOf("a", "b", "c")
        val packed = PackedLines.pack(texts)
        val out = PackedLines.unpack(texts, packed, listOf("ئا\nب\nج"))
        assertEquals(listOf("ئا", "ب", "ج"), out)
    }

    @Test
    fun `a wrong number of lines is refused, not guessed`() {
        val texts = listOf("a", "b", "c")
        val packed = PackedLines.pack(texts)
        val out = PackedLines.unpack(texts, packed, listOf("ئا ب ج"))
        assertEquals(texts, out)
    }

    @Test
    fun `empty lines are skipped without shifting the others`() {
        val texts = listOf("", "b", "")
        val packed = PackedLines.pack(texts)
        assertEquals(listOf(1), packed.indexGroups.single())
        val out = PackedLines.unpack(texts, packed, listOf("ب"))
        assertEquals(listOf("", "ب", ""), out)
    }
}
