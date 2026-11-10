package app.mangascribe.core

import app.mangascribe.translate.BatchPlanner
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
            val chars = batch.size * 1000
            assertTrue(chars <= BatchPlanner.MAX_CHARS_PER_REQUEST)
        }
    }

    @Test
    fun `batch count respects the line budget`() {
        val batches = BatchPlanner.plan(List(120) { "line" })
        assertTrue(batches.all { it.size <= BatchPlanner.MAX_LINES_PER_REQUEST })
        assertEquals(120, batches.sumOf { batch -> batch.size })
    }

    @Test
    fun `realign keeps original order and fills gaps`() {
        val source = listOf("one", "two", "three")
        val out = BatchPlanner.realign(source, listOf(1, 2), listOf("deux", ""))
        assertEquals(listOf("one", "deux", "three"), out)
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
