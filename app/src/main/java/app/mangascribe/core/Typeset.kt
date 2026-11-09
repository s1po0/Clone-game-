package app.mangascribe.core

/**
 * Bubble typesetting without Android graphics in the way: callers pass a [Measurer] so the
 * same logic runs under a JVM unit test. The renderer implements [Measurer] over
 * `android.graphics.Paint`.
 */
interface Measurer {
    fun width(text: String, sizePx: Float): Float
    /** Line height for a font size, including the leading the paint would add. */
    fun lineHeight(sizePx: Float): Float
}

data class TypesetResult(
    val lines: List<String>,
    val fontSize: Float,
    val lineHeight: Float,
    val blockHeight: Float,
    val blockWidth: Float,
    val overflows: Boolean,
) {
    val lineCount: Int get() = lines.size
}

/**
 * Fits translated text into a box: greedily wraps to the widest word boundary that fits, then
 * steps the font size down until the wrapped block is no taller than the box. This is the
 * reason a translated balloon still reads like a balloon instead of spilling over the panel.
 */
object TextFitter {

    const val MIN_FONT_PX = 9f

    fun fit(
        text: String,
        boxWidth: Float,
        boxHeight: Float,
        startSizePx: Float,
        measurer: Measurer,
        minSizePx: Float = MIN_FONT_PX,
        maxLines: Int = 60,
    ): TypesetResult {
        val clean = text.trim()
        if (clean.isEmpty() || boxWidth <= 1f || boxHeight <= 1f) {
            return TypesetResult(emptyList(), startSizePx, 0f, 0f, 0f, false)
        }
        var size = startSizePx.coerceAtLeast(minSizePx)
        var attempt = wrap(clean, boxWidth, size, measurer, maxLines)
        var guard = 0
        while (guard++ < 64 && attempt.size * measurer.lineHeight(size) > boxHeight && size > minSizePx) {
            size = (size * 0.92f).coerceAtLeast(minSizePx)
            attempt = wrap(clean, boxWidth, size, measurer, maxLines)
        }
        val height = attempt.size * measurer.lineHeight(size)
        val widest = attempt.maxOfOrNull { measurer.width(it, size) } ?: 0f
        return TypesetResult(
            lines = attempt,
            fontSize = size,
            lineHeight = measurer.lineHeight(size),
            blockHeight = height,
            blockWidth = widest,
            overflows = height > boxHeight + 0.5f,
        )
    }

    /**
     * Greedy wrap. CJK has no spaces, so a run of Han/Kana may break between any two
     * characters; Latin breaks on whitespace. Any line still wider than the box is hard-broken
     * afterwards, which is what keeps a long url or an unbroken CJK run inside its balloon.
     */
    fun wrap(
        text: String,
        maxWidth: Float,
        sizePx: Float,
        measurer: Measurer,
        maxLines: Int = 60,
    ): List<String> {
        val out = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) { out += ""; continue }
            var line = StringBuilder()
            fun flushLine() {
                if (line.isNotEmpty()) {
                    out += line.toString().trimEnd()
                    line = StringBuilder()
                }
            }
            for (token in tokenize(paragraph)) {
                if (line.isEmpty()) {
                    line = StringBuilder(token.trimStart())
                    continue
                }
                val candidate = line.toString() + token
                if (measurer.width(candidate.trimEnd(), sizePx) <= maxWidth) {
                    line = StringBuilder(candidate)
                } else {
                    flushLine()
                    line = StringBuilder(token.trimStart())
                }
            }
            flushLine()
            if (out.size >= maxLines) break
        }
        val broken = out.take(maxLines).flatMap { hardBreak(it, maxWidth, sizePx, measurer) }
        return broken.take(maxLines).ifEmpty { listOf("") }
    }

    /** Split one over-wide line into pieces that fit, by characters. */
    private fun hardBreak(line: String, maxWidth: Float, sizePx: Float, measurer: Measurer): List<String> {
        if (line.isEmpty() || measurer.width(line, sizePx) <= maxWidth) return listOf(line)
        val parts = mutableListOf<String>()
        var rest = line
        while (rest.isNotEmpty() && measurer.width(rest, sizePx) > maxWidth) {
            var cut = rest.length - 1
            while (cut > 1 && measurer.width(rest.substring(0, cut), sizePx) > maxWidth) cut--
            parts += rest.substring(0, cut)
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    /**
     * Split into wrap tokens. A CJK character is its own token; a Latin word keeps its
     * trailing space so widths stay honest.
     */
    fun tokenize(s: String): List<String> {
        val tokens = mutableListOf<String>()
        val word = StringBuilder()
        fun flush() { if (word.isNotEmpty()) { tokens += word.toString(); word.clear() } }
        for (c in s) {
            when {
                c == ' ' -> { word.append(c); flush() }
                isCjk(c) -> { flush(); tokens += c.toString() }
                else -> word.append(c)
            }
        }
        flush()
        return tokens
    }

    fun isCjk(c: Char): Boolean =
        c in '\u3040'..'\u30FF' || c in '\u3400'..'\u4DBF' || c in '\u4E00'..'\u9FFF' ||
            c in '\uAC00'..'\uD7AF' || c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'

    /**
     * Starting font size for a block: the recognised line height, clamped so a tiny sfx
     * does not become a headline and a wide caption does not become a wall.
     */
    fun startSizeFor(block: TextBlock, expansion: Float): Float {
        val seed = Geometry.fontSeedSize(block) * expansion
        return seed.coerceIn(10f, 160f)
    }
}

/**
 * Vertical typesetting for Japanese columns: characters run top to bottom, columns right to
 * left. Latin runs inside Japanese text are rotated 90 degrees (the classic 縦中横 problem);
 * we keep them upright and short, which reads correctly at bubble size.
 */
object VerticalTypeset {

    data class Column(val text: String, val x: Float)

    fun layout(text: String, boxWidth: Float, boxHeight: Float, charSize: Float): List<Column> {
        if (text.isBlank()) return emptyList()
        val perColumn = (boxHeight / charSize).toInt().coerceAtLeast(1)
        val flat = text.replace("\n", "").trim()
        if (flat.isEmpty()) return emptyList()
        val columns = flat.chunked(perColumn)
        val columnsFit = (boxWidth / charSize).toInt().coerceAtLeast(1)
        val used = columns.take(columnsFit)
        // x is the centre of each column, laid right-to-left
        return used.mapIndexed { i, s -> Column(s, boxWidth - charSize * (i + 0.5f)) }
    }
}
