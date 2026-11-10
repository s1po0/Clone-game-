package app.mangascribe.core

import app.mangascribe.data.Secrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential document, tested on the JVM. The Keystore half of SecretStore needs a device;
 * what can be wrong without one is the encoding, so that is what these cover: a key with
 * quotes, backslashes or a non-BMP emoji must survive a save/load cycle unchanged.
 */
class SecretsTest {

    @Test
    fun `round trips a normal key`() {
        val original = Secrets(llmApiKey = "sk-proj-abc123", libreApiKey = "", myMemoryEmail = "me@x.com")
        val back = Secrets.fromJson(original.toJson())
        assertEquals(original, back)
    }

    @Test
    fun `survives quotes, backslashes and unicode`() {
        val original = Secrets(
            llmApiKey = """sk-"quoted"\path\to\key""",
            libreApiKey = "key with spaces",
            myMemoryEmail = "kurdî@example.com 🙂",
        )
        val back = Secrets.fromJson(original.toJson())
        assertEquals(original, back)
    }

    @Test
    fun `base64 bundle round trips (export to another phone)`() {
        val original = Secrets(llmApiKey = "gsk_1234567890", libreApiKey = "libre-key")
        val bundle = Secrets.encode(original)
        assertEquals(original, Secrets.decode(bundle))
        // the bundle is not the raw key, so it is safe to paste into a note
        assertFalse(bundle.contains("gsk_1234567890"))
    }

    @Test
    fun `missing fields decode to empty, not to an exception`() {
        assertEquals(Secrets.EMPTY, Secrets.fromJson("{}"))
        assertEquals(Secrets.EMPTY, Secrets.fromJson("""{"llm":""}"""))
        assertEquals(Secrets.EMPTY, Secrets.fromJson(""))
    }

    @Test
    fun `truncated json does not hang or throw`() {
        val broken = """{"llm":"sk-abc"""
        val decoded = Secrets.fromJson(broken)
        assertEquals("sk-abc", decoded.llmApiKey)
    }

    @Test
    fun `empty means empty, so the file can be removed`() {
        assertTrue(Secrets.EMPTY.isEmpty)
        assertFalse(Secrets(llmApiKey = "x").isEmpty)
    }

    @Test
    fun `describe masks the value for logs`() {
        val described = Secrets(llmApiKey = "sk-abcdefgh1234").describe()
        assertTrue(described.contains("model=set"))
        assertTrue(described.contains("1234"))
        assertFalse(described.contains("abcdefgh"))
        assertTrue(described.contains("libre=unset"))
    }

    @Test
    fun `a different key produces a different document`() {
        assertNotEquals(
            Secrets(llmApiKey = "a").toJson(),
            Secrets(llmApiKey = "b").toJson(),
        )
    }
}
