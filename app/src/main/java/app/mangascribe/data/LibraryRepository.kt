package app.mangascribe.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.mangascribe.core.Languages
import app.mangascribe.core.TextBlock
import app.mangascribe.ocr.ImageSource
import app.mangascribe.ocr.MlKitOcrEngine
import app.mangascribe.ocr.OcrOptions
import app.mangascribe.render.PageRenderer
import app.mangascribe.render.RenderStyle
import app.mangascribe.translate.BatchPlanner
import app.mangascribe.translate.CachedTranslator
import app.mangascribe.translate.Glossary
import app.mangascribe.translate.GoogleTranslateTranslator
import app.mangascribe.translate.overlay
import app.mangascribe.translate.Http
import app.mangascribe.translate.LibreTranslateTranslator
import app.mangascribe.translate.MyMemoryTranslator
import app.mangascribe.translate.OpenAiCompatibleTranslator
import app.mangascribe.translate.Translator
import app.mangascribe.translate.TranslationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class PageTranslation(
    val pageId: Long,
    val balloons: List<Balloon>,
    val engine: String?,
    val elapsedMs: Long,
)

/**
 * Everything the UI asks for goes through here: importing pages, translating a page, saving
 * edits, exporting a translated page. The OCR and translation engines are parameters so a
 * test can drive the pipeline without a device.
 */
class LibraryRepository(
    private val context: Context,
    private val dao: BookDao,
    private val settings: SettingsStore,
    private val http: Http,
    private val ocr: MlKitOcrEngine,
) {
    private val renderer = PageRenderer()

    /** Shown once after an import that lost some files, instead of failing later. */
    @Volatile
    var lastImportWarning: String? = null
        private set

    fun clearImportWarning() { lastImportWarning = null }

    fun books(): Flow<List<BookEntity>> = dao.observeAll()
    fun favorites(): Flow<List<BookEntity>> = dao.observeFavorites()
    fun book(id: Long): Flow<BookEntity?> = dao.observe(id)
    fun pages(bookId: Long): Flow<List<PageEntity>> = dao.observePages(bookId)
    suspend fun findBook(id: Long): BookEntity? = dao.find(id)

    // ---- Import ---------------------------------------------------------------------------

    /** Imports already-persisted local image files (share sheet, archive unpack, demo). */
    suspend fun importFiles(
        title: String,
        files: List<File>,
        origin: String = "file",
        sourceLanguage: String? = null,
        targetLanguage: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val current = settings.current()
        val now = System.currentTimeMillis()
        require(files.isNotEmpty()) { "There are no pages to import" }
        // Verify every page before a book is created: a silently missing file turns into
        // "the page image could not be read" at translate time, which is too late to explain.
        val usable = files.filter { it.exists() && it.length() > 0 }
        require(usable.isNotEmpty()) {
            "None of the selected files could be read (they are empty or were moved)."
        }
        val bookId = dao.insert(
            BookEntity(
                title = title,
                sourceLanguage = sourceLanguage ?: current.sourceLanguage,
                targetLanguage = targetLanguage ?: current.targetLanguage,
                origin = origin,
                coverPath = "file://" + usable.first().absolutePath,
                createdAt = now,
                updatedAt = now,
            )
        )
        val pages = usable.mapIndexed { index, file ->
            PageEntity(bookId = bookId, index = index, imageUri = "file://" + file.absolutePath)
        }
        dao.insertPages(pages)
        refreshPageCount(bookId)
        bookId
    }

    /**
     * Best-effort display name for a content URI, used only for ordering pages. Never used as a
     * key: two providers may return the same name for different files.
     */
    private fun Uri.sortName(): String = runCatching {
        context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) cursor.getString(idx) ?: "" else ""
            } else ""
        }
    }.getOrNull().orEmpty().ifBlank { lastPathSegment.orEmpty() }

    suspend fun importUris(
        title: String,
        uris: List<Uri>,
        origin: String = "file",
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val current = settings.current()
        val folder = File(context.filesDir, "pages/${System.currentTimeMillis()}")
        folder.mkdirs()
        val saved = mutableListOf<File>()
        val failures = mutableListOf<String>()
        // The picker returns documents in tap order, which is almost never reading order.
        // Sort by the display name so `001.jpg, 002.jpg, 010.jpg` become pages 1,2,3 instead of
        // whatever order the user happened to select them in — the difference between a chapter
        // and a shuffled deck.
        val ordered = uris.sortedWith(compareBy({ it.sortName() }, { it.toString() }))
        ordered.forEachIndexed { index, uri ->
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val ext = when {
                mime == null -> "jpg"
                mime.contains("png") -> "png"
                mime.contains("webp") -> "webp"
                mime.contains("gif") -> "gif"
                mime.contains("heic") || mime.contains("heif") -> "heic"
                else -> "jpg"
            }
            val out = File(folder, "%04d.%s".format(index, ext))
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("the picker did not grant access to this file")
                out.length()
            }.getOrElse { error ->
                failures += "page ${index + 1}: ${error.message ?: "unreadable"}"
                0L
            }
            if (copied > 0) saved += out else out.delete()
        }
        // A share sheet can hand over a URI that dies the moment the sender goes away; the
        // copy above is why a page keeps working after that, and why the message below is
        // specific when it does not.
        require(saved.isNotEmpty()) {
            "No page could be copied: " + failures.take(2).joinToString("; ")
        }
        val bookId = dao.insert(
            BookEntity(
                title = title,
                sourceLanguage = current.sourceLanguage,
                targetLanguage = current.targetLanguage,
                origin = origin,
                coverPath = saved.firstOrNull()?.let { "file://" + it.absolutePath },
                createdAt = now,
                updatedAt = now,
            )
        )
        if (failures.isNotEmpty()) {
            lastImportWarning = "${failures.size} of ${uris.size} files could not be copied " +
                "(${failures.first()})"
        }
        dao.insertPages(saved.mapIndexed { index, file ->
            PageEntity(bookId = bookId, index = index, imageUri = "file://" + file.absolutePath)
        })
        refreshPageCount(bookId)
        bookId
    }

    suspend fun importArchive(uri: Uri, displayName: String): Long = withContext(Dispatchers.IO) {
        val name = displayName.substringBeforeLast('.').ifBlank { "Comic" }
        when {
            ArchiveImport.isArchive(displayName) -> {
                val imported = ArchiveImport.unpackZip(context, uri, name)
                require(imported.pages.isNotEmpty()) { "The archive holds no images" }
                importFiles(imported.title, imported.pages, origin = "archive")
            }
            ArchiveImport.isRar(displayName) ->
                throw IllegalArgumentException(
                    ".cbr is a RAR archive and cannot be opened here. Convert it to .cbz " +
                        "(rename to .zip works when the file is really a zip) and import again."
                )
            else -> importUris(name, listOf(uri), origin = "file")
        }
    }

    suspend fun renameBook(id: Long, title: String) {
        val book = dao.find(id) ?: return
        dao.update(book.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteBook(id: Long) {
        val book = dao.find(id) ?: return
        dao.delete(id)
        runCatching {
            val folder = book.coverPath?.removePrefix("file://")?.let { File(it).parentFile }
            folder?.deleteRecursively()
        }
    }

    // ---- Translation ----------------------------------------------------------------------

    /**
     * The one pipeline: decode → OCR → clean/merge → translate → typeset-ready balloons →
     * store. Failures land on the page row, so the reader shows the original art and a retry.
     */
    suspend fun translatePage(
        bookId: Long,
        index: Int,
        overrideTarget: String? = null,
    ): PageTranslation = withContext(Dispatchers.Default) {
        val page = dao.page(bookId, index) ?: error("Page $index is missing")
        val book = dao.find(bookId) ?: error("Book is missing")
        val config = settings.current()
        val started = System.currentTimeMillis()
        dao.setStatus(page.id, PageEntity.Status.RUNNING)
        try {
            // Ask the decoder why a page failed rather than turning every cause into one
            // useless sentence: a missing file, a revoked share and a 12000 px strip that ran
            // out of memory are different problems and get different text on the card.
            val dims = PageImages.dimensions(context, page.imageUri)
            val source = if (dims.first > 0 && dims.second > 0) {
                app.mangascribe.ocr.ImageSource.FileSource(
                    context, page.imageUri, dims.first, dims.second,
                )
            } else {
                val bmp = PageImages.decodeOrThrow(context, page.imageUri, PageImages.OCR_TARGET_WIDTH)
                app.mangascribe.ocr.ImageSource.BitmapSource(bmp)
            }
            val ocrResult = ocr.recognizeSource(
                source,
                languageCode = book.sourceLanguage,
                options = OcrOptions(
                    stripFurigana = config.stripFurigana,
                    mergeBlocks = config.mergeBlocks,
                ),
            )
            // Sizes for the reader come from the page itself, not from whatever was decoded.
            val pageWidth = if (dims.first > 0) dims.first else PageImages.READER_TARGET_WIDTH
            val pageHeight = if (dims.second > 0) dims.second else (pageWidth * 1.4f).toInt()
            if (ocrResult.blocks.isEmpty()) {
                dao.saveTranslation(
                    page.id, PageEntity.Status.DONE, "[]", "ocr-none",
                    "No text was found on this page", System.currentTimeMillis(),
                    pageWidth, pageHeight,
                )
                return@withContext PageTranslation(page.id, emptyList(), "ocr-none", ocrResult.elapsedMs)
            }
            // Kurdish is repaired before translation: the Arabic model's ك/ي/ھ are not Kurdish.
            val sourceLanguage = ocrResult.detectedLanguage ?: book.sourceLanguage
            val prepared = ocrResult.blocks.map { block ->
                if (app.mangascribe.core.Languages.of(sourceLanguage).script ==
                    app.mangascribe.core.Script.KURDISH
                ) {
                    block.copy(text = app.mangascribe.core.OcrCleanup.normaliseKurdish(block.text))
                } else {
                    block
                }
            }
            val target = overrideTarget ?: book.targetLanguage
            val texts = prepared.map { it.text.replace("\n", " ") }
            val pipeline = TranslationPipeline(engines(config), config.engineId)
            val batch = pipeline.translate(texts, sourceLanguage, target, deadEngines.toList())
            // The glossary wins over the engine on known lines: "What?!" and "Run!" then read
            // the same on every page of a chapter instead of drifting per request. Borrowed
            // from the Kurd-man pipeline, where it was the cheapest quality win of all.
            deadEngines.addAll(batch.unreachable)
            val lines = Glossary.builtIn(target).overlay(texts, batch.lines)
            // Store the finished pairs so a retry, a second read or the next chapter is local.
            runCatching {
                warmCache(
                    batch.engineId, sourceLanguage, target,
                    texts.indices.associate { texts[it] to lines[it] }
                        .filterValues { it.isNotBlank() },
                )
            }
            val balloons = prepared.mapIndexed { i, block ->
                Balloon.from(block, lines.getOrElse(i) { block.text })
                    .copy(maskOriginal = config.maskOriginal)
            }
            val w = pageWidth
            val h = pageHeight
            dao.saveTranslation(
                page.id,
                PageEntity.Status.DONE,
                BalloonCodec.encode(balloons),
                batch.engineId,
                null,
                System.currentTimeMillis(),
                w,
                h,
            )
            PageTranslation(page.id, balloons, batch.engineId, System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            // Say what happened and what to do about it. The raw engine error ("MyMemory
            // returned no text") is accurate and misleading: MyMemory is the fallback and has
            // no Kurdish; the reader needs to know Google is the engine that matters here.
            val message = if (t is app.mangascribe.translate.TranslationException) {
                app.mangascribe.translate.EngineFailures.explain(
                    tried = listOf("Google Translate" to t.message),
                    targetLanguage = overrideTarget ?: book.targetLanguage,
                    sourceLanguage = book.sourceLanguage,
                )
            } else {
                t.message ?: t.javaClass.simpleName
            }
            dao.setStatus(page.id, PageEntity.Status.FAILED, message)
            throw app.mangascribe.translate.TranslationException(message, t)
        }
    }

    /** Retranslate a page from stored OCR text — no second OCR pass, so it is quick. */
    suspend fun pagesOf(bookId: Long): List<PageEntity> = withContext(Dispatchers.IO) { dao.pages(bookId) }

    suspend fun retranslatePage(pageId: Long, targetLanguage: String): List<Balloon> =
        withContext(Dispatchers.Default) {
            val page = dao.pageById(pageId) ?: error("Page is missing")
            val book = dao.find(page.bookId) ?: error("Book is missing")
            val existing = BalloonCodec.decode(page.blocksJson)
            if (existing.isEmpty()) return@withContext emptyList()
            val config = settings.current()
            val pipeline = TranslationPipeline(engines(config), config.engineId)
            val batch = pipeline.translate(
                existing.map { it.sourceText.replace("\n", " ") },
                book.sourceLanguage,
                targetLanguage,
                deadEngines.toList(),
            )
            val sources = existing.map { it.sourceText.replace("\n", " ") }
            val lines = Glossary.builtIn(targetLanguage).overlay(sources, batch.lines)
            val updated = existing.mapIndexed { i, balloon ->
                balloon.copy(translatedText = lines.getOrElse(i) { balloon.translatedText })
            }
            dao.updateBlocks(pageId, BalloonCodec.encode(updated))
            updated
        }

    suspend fun translateBook(bookId: Long, onProgress: (done: Int, total: Int, failed: Int) -> Unit) {
        val pages = dao.pages(bookId)
        val book = dao.find(bookId) ?: return
        var failed = 0
        pages.forEachIndexed { i, page ->
            if (page.status == PageEntity.Status.DONE) {
                onProgress(i + 1, pages.size, failed)
                return@forEachIndexed
            }
            try {
                translatePage(bookId, page.index, book.targetLanguage)
            } catch (t: Throwable) {
                failed++
            }
            onProgress(i + 1, pages.size, failed)
        }
    }

    suspend fun saveBalloons(pageId: Long, balloons: List<Balloon>) {
        dao.updateBlocks(pageId, BalloonCodec.encode(balloons))
    }

    suspend fun pageBalloons(pageId: Long): List<Balloon> {
        val page = dao.pageById(pageId) ?: return emptyList()
        return BalloonCodec.decode(page.blocksJson)
    }

    /**
     * Exports the translated page as PNG into the app's cache and returns a shareable URI.
     * This is the "save result" path a reader uses to post a page or read it elsewhere.
     */
    suspend fun exportPage(pageId: Long): Uri? = withContext(Dispatchers.IO) {
        val page = dao.pageById(pageId) ?: return@withContext null
        val book = dao.find(page.bookId) ?: return@withContext null
        val balloons = BalloonCodec.decode(page.blocksJson)
        if (balloons.isEmpty()) return@withContext null
        val bitmap = PageImages.decode(context, page.imageUri, PageImages.READER_TARGET_WIDTH)
            ?: return@withContext null
        val config = settings.current()
        val targetLanguage = app.mangascribe.core.Languages.of(book.targetLanguage)
        val style = RenderStyle(
            paperColor = renderer.samplePaperColor(bitmap, balloons.first()),
            fontScale = config.fontScale,
            maskOriginal = config.maskOriginal,
            outlineText = config.outlineText,
            verticalTypesetting = config.verticalTypesetting,
            // Kurdish, Arabic and Persian are drawn right-aligned through the RTL path.
            rightToLeft = targetLanguage.rtl &&
                targetLanguage.script != app.mangascribe.core.Script.JAPANESE,
        )
        val rendered: Bitmap = renderer.renderInPlace(bitmap, balloons, style)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "page-${book.id}-${page.index}-${System.currentTimeMillis()}.png")
        FileOutputStream(out).use { rendered.compress(Bitmap.CompressFormat.PNG, 95, it) }
        bitmap.recycle()
        rendered.recycle()
        ExportProvider.uriFor(context, out)
    }

    suspend fun pageAt(bookId: Long, index: Int): PageEntity? = dao.page(bookId, index)

    private suspend fun refreshPageCount(bookId: Long) {
        val count = dao.pageCount(bookId)
        val book = dao.find(bookId) ?: return
        dao.update(book.copy(pageCount = count, updatedAt = System.currentTimeMillis()))
    }

    /** Whole library → one zip, for moving to a new phone or a differently-signed build. */
    suspend fun exportBackup(): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "backups").apply { mkdirs() }
            val file = File(dir, "mangascribe-backup-${System.currentTimeMillis()}.zip")
            val result = UpgradeHelper.exportLibrary(context, file)
            lastBackupSummary = "${result.books} book(s), ${result.pages} page(s)"
            ExportProvider.uriFor(context, result.file)
        }.getOrNull()
    }

    /** Restore a backup zip; the caller must restart the app afterwards. */
    suspend fun importBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(context.cacheDir, "restore-${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            val ok = UpgradeHelper.restoreLibrary(context, temp)
            temp.delete()
            ok
        }.getOrDefault(false)
    }

    @Volatile
    var lastBackupSummary: String? = null
        private set

    suspend fun setLastPage(bookId: Long, index: Int) = dao.setLastPage(bookId, index)
    suspend fun toggleFavorite(bookId: Long) = dao.toggleFavorite(bookId)
    suspend fun bookByPage(pageId: Long): BookEntity? =
        dao.pageById(pageId)?.let { dao.find(it.bookId) }

    /**
     * Engines that failed at the transport level (dead host, no route) during the current run.
     * A chapter is dozens of pages: retrying a hostname that does not resolve on every page
     * costs a DNS timeout each and buries the real error. Cleared when a page succeeds.
     */
    private val deadEngines = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Forget dead hosts so a reader who reconnects does not need to restart the app. */
    fun resetEngineHealth() {
        deadEngines.clear()
    }

    /** One cache for the whole app: a line translated on page 3 is free on page 90. */
    private val translationCache by lazy { TranslationCache(context) }

    private fun engines(config: AppSettings): List<Translator> = buildList {
        // Google first: it is the default, and the only free engine with real Kurdish Sorani.
        add(GoogleTranslateTranslator(http))
        add(MyMemoryTranslator(http))
        // LibreTranslate only when the user actually pointed it at a server. The public
        // argosopentech instance is dead (its hostname no longer resolves), so shipping it as a
        // default made every failing page end with "Unable to resolve host" — a DNS error for a
        // service the user never chose. A blank endpoint means "not configured", not "use mine".
        val libre = config.libreEndpoint.trim()
        if (libre.isNotEmpty() && !libre.contains("argosopentech")) {
            add(LibreTranslateTranslator(http, libre, config.libreApiKey.takeIf { it.isNotBlank() }))
        }
        if (config.llmApiKey.isNotBlank()) {
            add(
                OpenAiCompatibleTranslator(
                    http,
                    config.llmBaseUrl,
                    config.llmApiKey,
                    config.llmModel.ifBlank { "gpt-4o-mini" },
                )
            )
        }
    }.map { CachedTranslator(it, translationCache) }

    /**
     * Save any line that was already translated before this build's cache existed, so an old
     * chapter does not have to be sent to a server again just because caching is new.
     */
    suspend fun warmCache(engine: String, source: String, target: String, pairs: Map<String, String>) {
        if (pairs.isEmpty()) return
        translationCache.putAll(pairs.mapKeys { (text, _) ->
            TranslationCache.key(engine, source, target, text)
        })
    }

    suspend fun cacheSize(): Int = translationCache.size()

    suspend fun clearCache() = translationCache.clear()

    fun languageOf(code: String) = Languages.of(code)
}

object ExportProvider {
    fun uriFor(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
}
