package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading layout, written from a real complaint: a webtoon strip imported at 800×4200 was drawn
 * into a 420 dp row, so most of the page (and half the dialogue) sat outside the visible area and
 * the only way to read it was to pinch. A strip must be as tall as its width implies.
 */
class ReadingLayoutTest {

    private val width = 400f
    private val screenHeight = 800f

    @Test
    fun `a tall webtoon strip gets its true height at the screen width`() {
        // 800 x 4200 is a normal webtoon page: aspect 5.25
        val height = ReadingLayout.scrollHeightDp(800, 4200, width, screenHeight, ReadingMode.FIT_WIDTH)
        assertEquals(400f * 5.25f, height, 0.5f)
    }

    @Test
    fun `the old clamp that cut strips is gone`() {
        val height = ReadingLayout.scrollHeightDp(800, 5000, width, screenHeight, ReadingMode.FIT_WIDTH)
        // 6.25 aspect — the previous code stopped at 3.2, i.e. roughly half the page
        assertTrue("a 6:1 strip must be taller than 3.2 screens", height > width * 3.2f)
    }

    @Test
    fun `one page mode letterboxes to the screen instead of scrolling per pixel`() {
        val height = ReadingLayout.scrollHeightDp(800, 4200, width, screenHeight, ReadingMode.PAGE)
        assertEquals(screenHeight, height, 0.5f)
    }

    @Test
    fun `a corrupt image height cannot allocate an unbounded row`() {
        val height = ReadingLayout.scrollHeightDp(10, 200_000, width, screenHeight, ReadingMode.FIT_WIDTH)
        assertTrue(height <= width * 40f)
    }

    @Test
    fun `strips are recognised by aspect, not by pixel height`() {
        assertTrue(ReadingLayout.isStrip(1.9f))
        assertTrue(ReadingLayout.isStrip(5.2f))
        assertFalse(ReadingLayout.isStrip(1.4f))
        assertEquals(ReadingMode.FIT_WIDTH, ReadingLayout.defaultFor(4.0f))
        assertEquals(ReadingMode.PAGE, ReadingLayout.defaultFor(1.4f))
    }

    @Test
    fun `a page with no stored dimensions still gets a sane height`() {
        val height = ReadingLayout.scrollHeightDp(0, 0, width, screenHeight, ReadingMode.FIT_WIDTH)
        assertTrue(height > 100f)
        assertTrue(height < 2000f)
    }
}

class TypesetSeedTest {

    @Test
    fun `a long line in a wide low caption starts smaller than a short shout`() {
        val short = TypesetSeed.forBox(200f, 120f, "RUN!")
        val long = TypesetSeed.forBox(300f, 50f, "The bridge is out and the river is rising fast.")
        assertTrue(short > long)
    }

    @Test
    fun `the seed never starts below legibility or above absurdity`() {
        assertEquals(8f, TypesetSeed.forBox(4f, 4f, "x"), 0.01f)
        assertTrue(TypesetSeed.forBox(4000f, 4000f, "x") <= 96f)
    }
}
