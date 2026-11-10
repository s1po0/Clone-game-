package app.mangascribe.core

import app.mangascribe.data.PageImages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind page decoding. These are the rules that were wrong when a page
 * "could not be read": the sampler halved a tall thin strip until it was one pixel wide, and
 * a 12000 px webtoon page was never split into bands.
 */
class PageDecodeTest {

    @Test
    fun `a normal manga page is not sampled`() {
        assertEquals(1, PageImages.sampleSize(1400, 2000, PageImages.OCR_TARGET_WIDTH))
    }

    @Test
    fun `a huge page is sampled by powers of two`() {
        val sample = PageImages.sampleSize(6000, 9000, PageImages.OCR_TARGET_WIDTH)
        assertTrue(sample >= 2)
        assertEquals(0, Integer.highestOneBit(sample) xor sample) // a power of two
    }

    @Test
    fun `a tall strip is not halved into nothing`() {
        // 900 x 12000: halving by height alone would reach 56 x 750, then 28 x 375 ...
        val sample = PageImages.sampleSize(900, 12000, PageImages.OCR_TARGET_WIDTH)
        assertEquals(1, sample)
    }

    @Test
    fun `very long strips are read in bands`() {
        assertTrue(PageImages.needsBanding(800, 12000))
        assertFalse(PageImages.needsBanding(1400, 2000))
    }

    @Test
    fun `banding threshold matches a phone-sized page`() {
        // 1400 wide, 3200 tall is the tallest single pass the OCR engine allows
        assertFalse(PageImages.needsBanding(1400, 3200, 1400))
        assertTrue(PageImages.needsBanding(1400, 3400, 1400))
    }

    @Test
    fun `zero width is not banded`() {
        assertFalse(PageImages.needsBanding(0, 5000))
    }
}
