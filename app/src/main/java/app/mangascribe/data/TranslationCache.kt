package app.mangascribe.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A paragraph translated once is never sent to a server again.
 *
 * Comic chapters repeat themselves: "What?!", the protagonist's name, the same system label on
 * twenty pages, and — across a series — the same sound effects. The Kurd-man pipeline kept a
 * SQLite cache with a hit counter for exactly this reason, and it is the single best answer to
 * the failure the user hit: a free endpoint that rate-limits one page must never be asked twice
 * for the same sentence.
 *
 * Design notes:
 *  - The key includes the *preferred engine*, so switching engines gives that engine's own
 *    output rather than a cache hit from the previous one.
 *  - Values are stored per line, not per page, so a page edited in Edit Mode reuses nothing it
 *    should not.
 *  - The table is trimmed to [MAX_ROWS] by least-recently-used, so the file cannot grow without
 *    bound on a phone.
 *  - A cache hit costs one local read: no network, no rate limit, works offline.
 */
class TranslationCache(context: Context) {

    private val helper = object : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE t (k TEXT PRIMARY KEY, v TEXT NOT NULL, used INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX t_used ON t(used)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v2: answers written before the no-English rules are still in here. A cached
            // half-English line was replayed on every retranslate, so fixing the translator
            // changed nothing on screen — the same bubble came back from the cache. Wipe once.
            if (oldVersion < 3) {
                db.execSQL("DELETE FROM t")
            } else {
                db.execSQL("DROP TABLE IF EXISTS t")
                onCreate(db)
            }
        }
    }

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            helper.readableDatabase.rawQuery("SELECT v FROM t WHERE k = ?", arrayOf(key)).use { c ->
                if (!c.moveToFirst()) return@use null
                val value = c.getString(0)
                if (value.isBlank()) null else value
            }
        }.getOrNull()
    }

    /** Look up many keys in one round trip; missing keys are simply absent from the result. */
    suspend fun getAll(keys: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptyMap()
        runCatching {
            val out = HashMap<String, String>(keys.size)
            keys.chunked(200).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                helper.readableDatabase
                    .rawQuery("SELECT k, v FROM t WHERE k IN ($placeholders)", chunk.toTypedArray())
                    .use { c ->
                        while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
                    }
            }
            out
        }.getOrDefault(emptyMap())
    }

    suspend fun putAll(entries: Map<String, String>) = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext
        runCatching {
            val now = System.currentTimeMillis()
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                for ((k, v) in entries) {
                    if (v.isBlank()) continue
                    db.insertWithOnConflict("t", null, ContentValues().apply {
                        put("k", k)
                        put("v", v)
                        put("used", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            trim(db)
        }
    }

    private fun trim(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM t", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        if (count <= MAX_ROWS) return
        db.execSQL(
            "DELETE FROM t WHERE k IN (SELECT k FROM t ORDER BY used ASC LIMIT ?)",
            arrayOf(count - MAX_ROWS),
        )
    }

    suspend fun size(): Int = withContext(Dispatchers.IO) {
        runCatching {
            helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM t", null).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.getOrDefault(0)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { helper.writableDatabase.execSQL("DELETE FROM t") }
        Unit
    }

    companion object {
        private const val NAME = "translations-cache.db"
        /**
         * Bumped when a rule changes what an answer should look like. The cache key cannot carry
         * a rule version without losing every hit on every release, so the table is emptied once
         * per bump instead: a stale answer is worse than a fresh request.
         *
         *  2 — no Latin left in a Kurdish line; transliteration fallback; glossary term rewriting
         *  3 — transliterations are no longer cached at all, and the ones cached under v2 were
         *      meaningless lines that every retranslate replayed. Emptied again.
         */
        private const val VERSION = 3
        /** ~60k short lines: a few MB, and far more than a long series repeats. */
        private const val MAX_ROWS = 60_000

        /** Engine + pair + the exact text: the same words must map to the same answer. */
        fun key(engine: String, source: String, target: String, text: String): String =
            engine + "|" + source + "|" + target + "|" + text.trim().lowercase()
    }
}
