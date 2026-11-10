package app.mangascribe.core

/**
 * How a translated page is *read*, as opposed to how it is typeset.
 *
 * The renderer used to scale one fixed height by each page's aspect ratio, which is a decent
 * guess for a single manga panel and the wrong answer for a webtoon: a 3000 px strip got
 * clipped to a 420 dp box, so half the dialogue was drawn outside the visible page and the
 * reader had to pinch to see it. Reading a strip and reading one panel are different layouts,
 * so they are different modes rather than one formula with magic numbers.
 */
enum class ReadingMode(val label: String) {
    /** The whole page at the screen width, scrolling — right for webtoon strips. */
    FIT_WIDTH("Fit width"),

    /** One page per screen, letterboxed — right for paginated manga. */
    PAGE("One page"),

    /** Keep the page's own width and pan around it — for dense pages and small screens. */
    ZOOM("Zoom"),
}

object ReadingLayout {

    /**
     * A page is a strip when it is much taller than a phone screen would show at full width.
     * The threshold is in *aspect ratio* rather than pixels because that is what decides
     * whether the reader sees one bubble or ten at a time.
     */
    const val STRIP_ASPECT = 1.9f

    fun isStrip(aspect: Float): Boolean = aspect >= STRIP_ASPECT

    /**
     * The default mode for a page: strips scroll at full width, single pages sit one per
     * screen. A reader can override it, but the honest default needs no setting.
     */
    fun defaultFor(aspect: Float): ReadingMode = if (isStrip(aspect)) ReadingMode.FIT_WIDTH else ReadingMode.PAGE

    /**
     * Height in dp for a page drawn at [widthDp] in a scroll list.
     *
     * The old code clamped the ratio to 3.2, which cut a 6:1 webtoon strip at 40% of its
     * height. Strips now get their true height (bounded only by a sanity limit so one corrupt
     * image cannot allocate a 100 000 dp row); a single page is letterboxed into the screen.
     */
    fun scrollHeightDp(
        pageWidth: Int,
        pageHeight: Int,
        widthDp: Float,
        screenHeightDp: Float,
        mode: ReadingMode,
    ): Float {
        val aspect = if (pageWidth > 0 && pageHeight > 0) pageHeight.toFloat() / pageWidth else 1.4f
        return when (mode) {
            ReadingMode.FIT_WIDTH ->
                (widthDp * aspect).coerceIn(widthDp * 0.3f, widthDp * 40f)
            ReadingMode.PAGE ->
                screenHeightDp.coerceAtLeast(widthDp * 0.3f)
            ReadingMode.ZOOM ->
                (screenHeightDp * 1.2f).coerceAtLeast(widthDp * 0.3f)
        }
    }

    /**
     * Where the reader lands when a page is opened: the top of a strip, the previous position
     * of a paginated page. Returns null when no restoration is possible.
     */
    fun stripStartPage(startPage: Int, pageCount: Int): Int? =
        if (pageCount <= 0) null else startPage.coerceIn(0, pageCount - 1)
}

/**
 * Fonts for drawn text.
 *
 * `sans-serif-medium` handles Latin and Japanese well and Arabic script only through the
 * phone's fallback chain, which differs by manufacturer and sometimes lacks a Kurdish letter.
 * The bundled Noto Naskh removes that variable for RTL targets; Latin keeps the system face,
 * which is what comic lettering expects.
 */
object PageFonts {
    private var cached: android.graphics.Typeface? = null

    fun forText(rightToLeft: Boolean): android.graphics.Typeface {
        if (!rightToLeft) return android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        cached?.let { return it }
        val loaded = runCatching {
            android.graphics.Typeface.createFromAsset(null, "font/noto_naskh_arabic.ttf")
        }.getOrNull() ?: android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        cached = loaded
        return loaded
    }
}

/**
 * Where a balloon's type starts: big enough to read, small enough that the fitter's first
 * attempt is close. Derived from the box *and* the text, because a 300 px wide, 40 px tall
 * caption and a 200×200 bubble need different starting points even at the same area.
 */
object TypesetSeed {
    private fun min(a: Float, b: Float) = if (a < b) a else b

    fun forBox(boxWidth: Float, boxHeight: Float, text: String): Float {
        val byHeight = boxHeight * 0.92f
        val byWidth = boxWidth * 1.1f
        val short = text.trim().length <= 24
        val base = if (short) min(byHeight, byWidth) else min(byHeight, byWidth * 0.62f)
        return base.coerceIn(8f, 96f)
    }
}
