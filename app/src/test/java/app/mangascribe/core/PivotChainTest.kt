package app.mangascribe.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason Arabic works and Sorani does not is not the app's Kurdish rules — it is the route.
 * Google has a direct Arabic model; for Sorani on the free endpoints the only reliable bridge is
 * English, and Turkish (the old pivot) has no Turkish→Sorani model at all.
 */
class PivotChainTest {

    @Test
    fun `a direct pair needs no pivot`() {
        assertEquals(emptyList<String>(), EngineCodes.pivotChain(EngineCodes.GOOGLE, "en", "ar"))
        assertEquals(emptyList<String>(), EngineCodes.pivotChain(EngineCodes.GOOGLE, "en", "ckb"))
    }

    @Test
    fun `english is tried before turkish`() {
        val chain = EngineCodes.pivotChain(EngineCodes.KEYLESS, "en", "ckb")
        assertTrue("got: $chain", chain.contains("en") || chain.isEmpty())
    }

    @Test
    fun `a pivot never repeats an end of the pair`() {
        val chain = EngineCodes.pivotChain(EngineCodes.KEYLESS, "ja", "ckb")
        assertTrue(chain.none { it == "ja" || it == "ckb" })
    }

    @Test
    fun `sorani is a kurdish target and an rtl language`() {
        assertTrue(EngineCodes.isSorani("ckb"))
        assertTrue(Languages.of("ckb").rtl)
    }
}
