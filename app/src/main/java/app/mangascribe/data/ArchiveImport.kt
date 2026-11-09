package app.mangascribe.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Comic archives: .cbz and .zip open directly; .cbr (RAR) is refused with a plain explanation
 * rather than a crash, since there is no licence-free RAR reader worth shipping. Pages are
 * unpacked into the app's own cache so a SAF URI does not have to stay granted.
 */
object ArchiveImport {

    val SUPPORTED = listOf("cbz", "zip")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "avif")

    data class ImportedBook(val title: String, val pages: List<File>)

    fun isArchive(name: String): Boolean =
        SUPPORTED.any { name.lowercase().endsWith(".$it") }

    fun isRar(name: String): Boolean = name.lowercase().endsWith(".cbr") || name.lowercase().endsWith(".rar")

    suspend fun unpackZip(context: Context, uri: Uri, title: String): ImportedBook =
        withContext(Dispatchers.IO) {
            val target = File(context.filesDir, "books/${sanitize(title)}-${System.currentTimeMillis()}")
            if (!target.exists()) target.mkdirs()
            val pages = mutableListOf<File>()
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream.buffered()).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    var guard = 0
                    while (entry != null && guard++ < 5000) {
                        val name = entry.name
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val isImage = ext in IMAGE_EXTENSIONS &&
                            !name.startsWith("__MACOSX") &&
                            !name.substringAfterLast('/').startsWith(".")
                        if (!entry.isDirectory && isImage) {
                            val out = File(target, "%04d.%s".format(pages.size, ext))
                            out.outputStream().use { zip.copyTo(it) }
                            pages += out
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
            ImportedBook(title, pages.sortedBy { it.name })
        }

    /** Natural sort: page2 before page10, which plain string sorting gets wrong. */
    fun naturalSortNames(names: List<String>): List<String> {
        val chunked = Regex("(\\d+|\\D+)")
        return names.sortedWith { a, b ->
            val ca = chunked.findAll(a).map { it.value }.toList()
            val cb = chunked.findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(ca.size, cb.size)) {
                val x = ca[i]
                val y = cb[i]
                val nx = x.toLongOrNull()
                val ny = y.toLongOrNull()
                val cmp = if (nx != null && ny != null) nx.compareTo(ny) else x.compareTo(y, ignoreCase = true)
                if (cmp != 0) return@sortedWith cmp
            }
            ca.size.compareTo(cb.size)
        }
    }

    fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9-_ ]"), "").trim().take(40).ifBlank { "book" }

    /**
     * Guess a target reading order a webtoon reader expects: strips are long, so a page is
     * treated as a vertical strip when height/width > 2.4.
     */
    fun isWebtoonStrip(width: Int, height: Int): Boolean = width > 0 && height.toFloat() / width > 2.4f
}
