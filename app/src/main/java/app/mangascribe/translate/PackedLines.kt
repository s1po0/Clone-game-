package app.mangascribe.translate

/**
 * Pack a page of bubbles into as few requests as the endpoint allows.
 *
 * Copied from the approach that makes Sorani work in practice: send a block of text of up to
 * 1200 characters per request, join the lines with newlines, and split the answer back into the
 * same lines. Twenty bubbles become one or two requests instead of twenty, which matters because
 * the free endpoint rate-limits per request, not per character.
 *
 * Two things make the unpacking safe:
 *  - the separator is a newline, and the parser keeps paragraph breaks, so
 *  - a request whose answer does not come back with the same number of lines is **rejected**
 *    rather than guessed at (a mismatched split would put one bubble's words in another).
 */
object PackedLines {

    /** What the working script uses. Longer chunks get truncated or rate-limited. */
    const val MAX_CHARS = 1200

    data class Packed(
        /** One request each, newline-separated lines inside. */
        val requests: List<String>,
        /** For each request, the indices of the source lines it carries. */
        val indexGroups: List<List<Int>>,
    )

    fun pack(texts: List<String>): Packed {
        val requests = mutableListOf<String>()
        val groups = mutableListOf<List<Int>>()
        var current = StringBuilder()
        var currentIndices = mutableListOf<Int>()

        fun flush() {
            if (currentIndices.isNotEmpty()) {
                requests += current.toString()
                groups += currentIndices.toList()
                current = StringBuilder()
                currentIndices = mutableListOf()
            }
        }

        texts.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (text.isEmpty()) return@forEachIndexed
            // A single line longer than the limit goes alone; it is split by the engine.
            val cost = text.length + 1
            if (currentIndices.isNotEmpty() && current.length + cost > MAX_CHARS) flush()
            if (currentIndices.isNotEmpty()) current.append('\n')
            current.append(text)
            currentIndices += index
            if (current.length >= MAX_CHARS) flush()
        }
        flush()
        return Packed(requests, groups)
    }

    /**
     * Put answers back in place. A section is accepted only when the engine returned one line per
     * line. Anything else leaves those lines untouched, which the caller then retries per line —
     * a wrong split is worse than a second request.
     */
    fun unpack(texts: List<String>, packed: Packed, answers: List<String?>): List<String> {
        val out = texts.toMutableList()
        packed.indexGroups.forEachIndexed { requestIndex, indices ->
            val answer = answers.getOrNull(requestIndex)?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            val sections = answer.split('\n').map { it.trim() }
            if (sections.size == indices.size) {
                indices.forEachIndexed { position, original ->
                    val value = sections.getOrNull(position)
                    if (!value.isNullOrBlank()) out[original] = value
                }
            }
            // size mismatch: leave the lines alone, the caller retries them one by one
        }
        return out
    }
}
