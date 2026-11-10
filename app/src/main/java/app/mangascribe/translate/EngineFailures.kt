package app.mangascribe.translate

/**
 * What to do when every engine fails — the message the reader actually sees.
 *
 * The user hit `MyMemory could not translate this pair: MyMemory returned no text`. That is
 * three quarters of the truth: MyMemory is the *second* engine and has no Kurdish at all, so its
 * failure is expected; the real story is that Google did not answer and nothing else here can
 * translate Kurdish. The Kurd-man pipeline made the same mistake ("all translators failed") and
 * the fix there is the fix here: name what failed, then say what to do.
 */
object EngineFailures {

    /** True when the engine genuinely has no route for this pair (a routing problem, not a network one). */
    fun isPairProblem(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("could not translate this pair") ||
            m.contains("returned no text") ||
            m.contains("no translator here can do") ||
            m.contains("unsupported")
    }

    /**
     * [tried] is the list of engines that were attempted, in order, with their own error if there
     * was one. The result is one paragraph: what happened, then the single most useful action.
     */
    fun explain(
        tried: List<Pair<String, String?>>,
        targetLanguage: String,
        sourceLanguage: String,
    ): String {
        val names = tried.map { it.first }
        val reasons = tried.mapNotNull { it.second }.distinct()
        val sorani = app.mangascribe.core.EngineCodes.isSorani(targetLanguage)

        val head = when {
            names.isEmpty() ->
                "No translation engine is set up for this. Open Settings → Translation engine."
            names.size == 1 -> "${names.first()} did not answer."
            else -> "No engine could translate this page (tried ${names.joinToString(", ")})."
        }
        val why = if (reasons.isEmpty()) "" else " " + reasons.first()
        val advice = buildString {
            append(' ')
            when {
                sorani -> append(
                    "For Kurdish the only free engine that really works is Google Translate — " +
                        "make sure it is selected in Settings → Translation engine, then retry. " +
                        "If Google keeps failing, the phone's connection may be blocking it; a " +
                        "model key (Settings → Set up a model) is the reliable fallback.",
                )
                isPairProblem(reasons.joinToString(" ")) -> append(
                    "This language pair is not supported by the free engines. A model key in " +
                        "Settings → Set up a model can translate it.",
                )
                else -> append(
                    "Check the connection and tap Translate again.) The page keeps its place.",
                )
            }
        }
        return (head + why + advice).replace("..", ".").replace(".)", ".")
    }

    /** One line for a status card, when the full paragraph would not fit. */
    fun short(message: String): String = message.substringBefore(".").takeIf { it.isNotBlank() }
        ?: "Translation failed"
}
