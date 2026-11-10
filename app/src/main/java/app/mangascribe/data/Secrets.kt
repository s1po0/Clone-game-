package app.mangascribe.data

import java.util.Base64

/** The three credentials the app can hold, and nothing else. */
data class Secrets(
    val llmApiKey: String = "",
    val libreApiKey: String = "",
    val myMemoryEmail: String = "",
) {
    val isEmpty: Boolean get() = toJson() == EMPTY_JSON

    fun toJson(): String = buildString {
        append('{')
        append("\"llm\":").append(quote(llmApiKey)).append(',')
        append("\"libre\":").append(quote(libreApiKey)).append(',')
        append("\"mm\":").append(quote(myMemoryEmail))
        append('}')
    }

    /** A masked form for logs and diagnostics: never the value itself. */
    fun describe(): String = buildString {
        fun one(name: String, value: String) {
            append(name).append('=')
            append(when {
                value.isBlank() -> "unset"
                value.length <= 4 -> "set(").append(value.length).append(" chars)"
                else -> "set(").append(value.length).append(" chars, …")
                    .append(value.takeLast(4)).append(')')
            })
            append(' ')
        }
        one("model", llmApiKey)
        one("libre", libreApiKey)
        one("mymemory", myMemoryEmail)
    }.trim()

    companion object {
        val EMPTY = Secrets()
        private val EMPTY_JSON = "{\"llm\":\"\",\"libre\":\"\",\"mm\":\"\"}"

        /** Small, dependency-free JSON: three known string fields, no nesting. */
        fun fromJson(json: String): Secrets {
            fun field(name: String): String {
                val key = "\"$name\":"
                val at = json.indexOf(key)
                if (at < 0) return ""
                var i = json.indexOf('"', at + key.length)
                if (i < 0) return ""
                i++
                val out = StringBuilder()
                while (i < json.length) {
                    val c = json[i]
                    when {
                        c == '\\' && i + 1 < json.length -> {
                            when (json[i + 1]) {
                                'n' -> out.append('\n')
                                't' -> out.append('\t')
                                'r' -> out.append('\r')
                                '"' -> out.append('"')
                                '\\' -> out.append('\\')
                                'u' -> {
                                    val hex = json.substring(i + 2, minOf(json.length, i + 6))
                                    out.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                                    i += 4
                                }
                                else -> out.append(json[i + 1])
                            }
                            i += 2
                        }
                        c == '"' -> return out.toString()
                        else -> { out.append(c); i++ }
                    }
                }
                return out.toString()
            }
            return Secrets(
                llmApiKey = field("llm").trim(),
                libreApiKey = field("libre").trim(),
                myMemoryEmail = field("mm").trim(),
            )
        }

        private fun quote(value: String): String {
            val out = StringBuilder("\"")
            for (c in value) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
                }
            }
            return out.append('"').toString()
        }

        /**
         * Base64 round-trip for the export/import bundle. java.util.Base64 rather than
         * android.util.Base64 so this half stays unit-testable on a plain JVM.
         */
        fun encode(secrets: Secrets): String =
            Base64.getEncoder().encodeToString(secrets.toJson().toByteArray(Charsets.UTF_8))

        fun decode(base64: String): Secrets = runCatching {
            fromJson(String(java.util.Base64.getMimeDecoder().decode(base64.trim()), Charsets.UTF_8))
        }.getOrDefault(EMPTY)
    }
}
