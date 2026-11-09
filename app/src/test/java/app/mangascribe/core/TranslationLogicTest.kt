package app.mangascribe.core

import app.mangascribe.translate.BatchPlanner
import app.mangascribe.translate.ChatPrompt
import app.mangascribe.translate.LibreTranslateParser
import app.mangascribe.translate.MyMemoryParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPlannerTest {

    @Test
    fun `short lists make one batch`() {
        val batches = BatchPlanner.plan(listOf("a", "b", "c"))
        assertEquals(1, batches.size)
        assertEquals(listOf(0, 1, 2), batches[0])
    }

    @Test
    fun `long lists split at the character budget`() {
        val line = "x".repeat(1000)
        val batches = BatchPlanner.plan(List(10) { line })
        assertTrue(batches.size >= 3)
        batches.forEach { batch ->
            val chars = batch.sumOf { 1000 }
            assertTrue(chars <= BatchPlanner.MAX_CHARS_PER_REQUEST)
        }
    }

    @Test
    fun `batch count respects the line budget`() {
        val batches = BatchPlanner.plan(List(120) { "line" })
        assertTrue(batches.all { it.size <= BatchPlanner.MAX_LINES_PER_REQUEST })
        assertEquals(120, batches.sumOf { it.size })
    }

    @Test
    fun `realign keeps original order and fills gaps`() {
        val source = listOf("one", "two", "three")
        val out = BatchPlanner.realign(source, listOf(1, 2), listOf("deux", ""))
        assertEquals(listOf("one", "deux", "three"), out)
    }
}

class ChatPromptTest {

    @Test
    fun `parses a numbered reply`() {
        val reply = "1. Hello there\n2. Goodbye\n3. See you"
        assertEquals(listOf("Hello there", "Goodbye", "See you"), ChatPrompt.parseNumbered(reply, 3))
    }

    @Test
    fun `tolerates parens, colons and quotes`() {
        val reply = "1) \"Hi\"\n2: Bye"
        assertEquals(listOf("Hi", "Bye"), ChatPrompt.parseNumbered(reply, 2))
    }

    @Test
    fun `refuses a reply with a missing line`() {
        assertNull(ChatPrompt.parseNumbered("1. only one", 2))
    }

    @Test
    fun `refuses a reply the model renumbered`() {
        assertNull(ChatPrompt.parseNumbered("10. shifted\n20. lines", 2))
    }

    @Test
    fun `build numbers every line`() {
        val prompt = ChatPrompt.build("Japanese", "English", listOf("おはよう", "こんばんは"))
        assertTrue(prompt.contains("1. おはよう"))
        assertTrue(prompt.contains("2. こんばんは"))
    }

    @Test
    fun `extracts content from a chat completion`() {
        val json = """
            {"choices":[{"message":{"role":"assistant","content":"1. Hello"}}]}
        """.trimIndent()
        assertEquals("1. Hello", ChatPrompt.extractContent(json))
    }
}

class ResponseParsersTest {

    @Test
    fun `mymemory translates`() {
        val json = """{"responseData":{"translatedText":"Hello there"},"responseStatus":200}"""
        assertEquals("Hello there", MyMemoryParser.parse(json))
    }

    @Test
    fun `mymemory quota apology is not a translation`() {
        val json = """{"responseData":{"translatedText":"PLEASE SELECT TWO DISTINCT LANGUAGES"}}"""
        assertNull(MyMemoryParser.parse(json))
    }

    @Test
    fun `libretranslate array`() {
        val json = """{"translatedText":["a","b"]}"""
        assertEquals(listOf("a", "b"), LibreTranslateParser.parse(json))
    }

    @Test
    fun `libretranslate error body is refused`() {
        assertNull(LibreTranslateParser.parse("""{"error":"Slowdown"}"""))
    }
}

class BalloonCodecTest {

    @Test
    fun `round trips balloons`() {
        val balloons = listOf(
            app.mangascribe.data.Balloon(
                id = 3,
                rect = RectF(10f, 20f, 110f, 80f),
                sourceText = "おまえ",
                translatedText = "You",
                angleDegrees = -4.5f,
                vertical = true,
            )
        )
        val json = app.mangascribe.data.BalloonCodec.encode(balloons)
        val back = app.mangascribe.data.BalloonCodec.decode(json)
        assertEquals(1, back.size)
        assertEquals("You", back[0].translatedText)
        assertEquals(10f, back[0].rect.left, 0.01f)
        assertEquals(true, back[0].vertical)
    }

    @Test
    fun `broken json decodes to empty, never throws`() {
        assertEquals(emptyList<Any>(), app.mangascribe.data.BalloonCodec.decode("{not json"))
        assertEquals(emptyList<Any>(), app.mangascribe.data.BalloonCodec.decode(null))
    }
}
