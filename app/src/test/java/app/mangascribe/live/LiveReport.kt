package app.mangascribe.live

import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.OkHttpHttp
import kotlinx.coroutines.runBlocking

/**
 * A one-shot report from the real network, printed to stdout by the CI job.
 *
 * Separate from the tests because the interesting output is the *whole* pipeline on a realistic
 * page — the status box from the screenshot, an English dialogue line, and a SFX — and because a
 * human reads it in the build log.
 */
object LiveReport {
    @JvmStatic
    fun main(args: Array<String>) {
        val engine = KeylessTranslateTranslator(OkHttpHttp(), lingvaInstances = emptyList())
        val page = listOf(
            "Aspect: Temple Slave",
            "description: a slave is a useless wretch with no notable skills or abilities",
            "what the actual fuck is this?!",
            "who are you?",
            "PY AAAHHH",
        )
        val batch = runBlocking { engine.translate(page, "auto", "ckb") }
        println("== live translation report ==")
        page.zip(batch.lines).forEach { (source, target) ->
            println("EN | $source")
            println("KU | $target")
            val latin = target.any { it in 'A'..'Z' || it in 'a'..'z' }
            println("   | ${if (latin) "FAIL: latin letters survived" else "OK: kurdish only"}")
        }
    }
}
