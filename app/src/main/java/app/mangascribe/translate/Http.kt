package app.mangascribe.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Small HTTP surface so translators can be unit-tested with a fake. */
interface Http {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String
    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String
}

class OkHttpHttp(
    /**
     * Short timeouts on purpose. A page makes several requests through a rotation of hosts; a
     * single host that silently drops packets must cost seconds, not a minute and a half, or the
     * reader sits on "Reading the page" while one dead endpoint holds the whole page.
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : Http {
    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun get(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).get()
            headers.forEach { (k, v) -> builder.header(k, v) }
            execute(builder.build())
        }

    override suspend fun postJson(url: String, body: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).post(body.toRequestBody(json))
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("Content-Type", "application/json")
            execute(builder.build())
        }

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw TranslationException("HTTP ${response.code}: ${text.take(180)}")
            }
            text
        }
}
