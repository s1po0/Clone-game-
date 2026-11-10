package app.mangascribe.live

import app.mangascribe.translate.OkHttpHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Which shape does Microsoft's keyless endpoint actually accept today?
 *
 * It answered 400 to the form the current readers report, so rather than guess again this probes
 * every plausible variant and prints the status of each. The answer goes into
 * `ci-live-report.txt`.
 */
class LiveMicrosoftProbe {

    private val live = System.getenv("MASCROBE") ?: System.getenv("MANGASCRIBE_LIVE")
    private val base = "https://edge.microsoft.com/translate/translatetext"

    @Test
    fun `probe every plausible shape`() {
        assumeTrue(live == "1")
        val http = OkHttpHttp()
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"
        val variants = listOf(
            "array ckb" to ("$base?from=en&to=ckb&isEnterpriseClient=false" to """["hello"]"""),
            "array ckb no-ent" to ("$base?from=en&to=ckb" to """["hello"]"""),
            "array ckb detect" to ("$base?to=ckb&isEnterpriseClient=false" to """["hello"]"""),
            "array ku" to ("$base?from=en&to=ku&isEnterpriseClient=false" to """["hello"]"""),
            "array en-ar" to ("$base?from=en&to=ar&isEnterpriseClient=false" to """["hello"]"""),
            "v3 object ckb" to (
                "https://api-edge.cognitive.microsofttranslator.com/translate?api-version=3.0&to=ckb"
                    to """[{"Text":"hello"}]"""
                ),
            "array zh sanity" to ("$base?from=en&to=zh-Hans&isEnterpriseClient=false" to """["hello"]"""),
        )
        variants.forEach { (name, pair) ->
            val (url, body) = pair
            val result = runCatching {
                runBlocking {
                    http.postJson(url, body, mapOf("User-Agent" to ua, "Accept" to "application/json"))
                }
            }
            val shown = result.getOrNull()?.take(160)?.replace("\n", " ")
                ?: result.exceptionOrNull()?.message?.take(160)
            println("PROBE $name → $shown")
        }
    }
}
