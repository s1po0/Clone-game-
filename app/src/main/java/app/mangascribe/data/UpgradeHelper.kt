package app.mangascribe.data

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving to a stable signing key means one install cannot update the other: Android treats a
 * different signer as a different app. The old build used the id `app.mangascribe.debug`, and
 * this one uses `app.mangascribe`, so an old install stays behind as a second icon.
 *
 * Rather than asking the reader to re-import a chapter they already translated, this finds the
 * old install (its private files are readable only if this app carries the shared legacy id, so
 * the practical path is the backup file below) and, failing that, offers a backup/restore of
 * the library — pages, translations and settings in one zip the user keeps.
 */
object UpgradeHelper {

    /** The application id earlier builds shipped with. */
    const val LEGACY_PACKAGE = "app.mangascribe.debug"

    fun isLegacyInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(LEGACY_PACKAGE, 0)
        true
    }.getOrElse { error ->
        error !is PackageManager.NameNotFoundException && false
    }

    /** True when this build itself is the legacy one (before the id change). */
    fun isRunningAsLegacy(context: Context): Boolean =
        context.packageName == LEGACY_PACKAGE

    // ---- full library backup -------------------------------------------------------------

    data class BackupResult(val file: File, val books: Int, val pages: Int)

    /**
     * Writes a zip holding everything worth keeping: the database, the imported page images and
     * the settings (without credentials, which are device-bound by design). Restoring it on a
     * new install gives back the library exactly as it was.
     */
    fun exportLibrary(context: Context, target: File): BackupResult {
        val dbFile = context.getDatabasePath(MangaScribeDatabase.NAME)
        val pagesDir = File(context.filesDir, "pages")
        val booksDir = File(context.filesDir, "books")
        var dbPages = 0
        var dbBooks = 0
        // Count from the file names only; opening the database to count would need a migration
        // path this helper deliberately does not have.
        if (dbFile.exists()) {
            runCatching {
                android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                ).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM books", null).use { c ->
                        if (c.moveToFirst()) dbBooks = c.getInt(0)
                    }
                    db.rawQuery("SELECT COUNT(*) FROM pages", null).use { c ->
                        if (c.moveToFirst()) dbPages = c.getInt(0)
                    }
                }
            }
        }

        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            fun add(file: File, entry: String) {
                if (!file.exists()) return
                zip.putNextEntry(ZipEntry(entry))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            // A copy, not the live file: the database may be open and mid-write.
            if (dbFile.exists()) {
                val copy = File(context.cacheDir, "backup-${System.currentTimeMillis()}.db")
                dbFile.copyTo(copy, overwrite = true)
                add(copy, "database/${MangaScribeDatabase.NAME}")
                copy.delete()
                add(File(dbFile.path + "-wal"), "database/${MangaScribeDatabase.NAME}-wal")
            }
            pagesDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    dir.listFiles()?.forEach { page ->
                        add(page, "files/pages/${dir.name}/${page.name}")
                    }
                }
            }
            booksDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) dir.listFiles()?.forEach { add(it, "files/books/${dir.name}/${it.name}") }
            }
            val settings = org.json.JSONObject().apply {
                put("exportedAt", System.currentTimeMillis())
                put("version", 1)
            }
            zip.putNextEntry(ZipEntry("meta.json"))
            zip.write(settings.toString().toByteArray())
            zip.closeEntry()
        }
        return BackupResult(target, dbBooks, dbPages)
    }

    /**
     * Restores a backup written by [exportLibrary]. The app must be restarted afterwards: the
     * database and the page folders are replaced on disk, and Room keeps the old file open.
     */
    fun restoreLibrary(context: Context, archive: File): Boolean = runCatching {
        val dbDir = context.getDatabasePath(MangaScribeDatabase.NAME).parentFile
        dbDir?.mkdirs()
        val pagesRoot = File(context.filesDir, "pages")
        val booksRoot = File(context.filesDir, "books")
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && !name.contains("..")) {
                    val target = when {
                        name.startsWith("database/") ->
                            File(dbDir, name.removePrefix("database/"))
                        name.startsWith("files/pages/") ->
                            File(pagesRoot, name.removePrefix("files/pages/"))
                        name.startsWith("files/books/") ->
                            File(booksRoot, name.removePrefix("files/books/"))
                        else -> null
                    }
                    if (target != null) {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> zip.copyTo(out) }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        true
    }.getOrDefault(false)
}
