package app.mangascribe.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import app.mangascribe.core.Languages
import app.mangascribe.core.KurdishTransliteration
import app.mangascribe.core.TranslatedTextCleanup
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
import app.mangascribe.translate.HostHealth
import app.mangascribe.translate.KeylessTranslateTranslator
import app.mangascribe.translate.LineTranslator
import app.mangascribe.translate.overlay
import app.mangascribe.translate.Http
import app.mangascribe.translate.LibreTranslateTranslator
import app.mangascribe.translate.MyMemoryTranslator
import app.mangascribe.translate.OpenAiCompatibleTranslator
import app.mangascribe.translate.Translator
import app.mangascribe.translate.TranslationPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
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
            // A detected source that equals the target is a dead end, not a language: the
            // engine is asked for ckb→ckb, Google returns the input untouched (HTTP 200, no
            // error), the no-Latin rescue then transliterates an English line, and the reader
            // gets Kurdish letters with no meaning — the screenshot that says "make it mean
            // something". The book's own declaration is the honest answer; the engine is told
            // `auto` and detects for itself.
            val detected = ocrResult.detectedLanguage
            val sourceLanguage = if (detected != null && detected == (overrideTarget ?: book.targetLanguage)) {
                book.sourceLanguage.takeIf { it != (overrideTarget ?: book.targetLanguage) } ?: "auto"
            } else {
                detected ?: book.sourceLanguage
            }
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
            // A page must finish or fail, never hang: every engine has retries, and retries over
            // a blocked network can add up. The budget is generous for a real chapter and short
            // enough that the reader sees a reason instead of a spinner.
            val translated = withTimeoutOrNull(PAGE_TRANSLATION_BUDGET_MS) {
                translateLines(texts, sourceLanguage, target, config)
            } ?: throw app.mangascribe.translate.TranslationException(
                "Translation took too long (over ${PAGE_TRANSLATION_BUDGET_MS / 1000} seconds). " +
                    "The engines may be blocked on this network — check the connection, then " +
                    "tap Translate again. Anything already translated for this page is kept.",
            )
            val lines = translated.lines
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
                translated.engineId,
                null,
                System.currentTimeMillis(),
                w,
                h,
            )
            PageTranslation(page.id, balloons, translated.engineId, System.currentTimeMillis() - started)
        } catch (t: kotlinx.coroutines.CancellationException) {
            // Stopped by the reader, or out of budget mid-OCR: the row must not stay "running"
            // or the page looks busy forever and the retry button is unreachable.
            dao.setStatus(page.id, PageEntity.Status.FAILED, "Stopped before it finished")
            throw t
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

    /**
     * One page's text, end to end: glossary first (free and exact), then the engines, then a
     * second pass for any line that still has source-language words in it.
     *
     * The order matters. A glossary hit costs nothing and is stable for the chapter; a term found
     * *inside* a line ("Aspect: Temple Slave") is substituted in place and the rest is still sent
     * to an engine; whatever comes back is verified word by word. That verification is the part
     * the screenshot proved necessary: an engine can translate half a line and return the other
     * half untouched, and a half-English bubble is worse than no translation at all.
     */
    private suspend fun translateLines(
        texts: List<String>,
        sourceLanguage: String,
        target: String,
        config: AppSettings,
    ): LineTranslator.Result {
        val glossary = Glossary.builtIn(target)
        // 1. Whole-line glossary (exact phrases, names, system labels).
        val afterWhole = texts.map { glossary.translationFor(it) }
        // 2. What is left goes to the engine **as written**.
        //
        // Pre-substituting Kurdish terms into an English line (an earlier idea) made the engine
        // detect the source as Kurdish and answer in English — a handful of Kurdish words inside
        // an English sentence is a strong script signal. The glossary is applied afterwards, on
        // the answer, and the raw line is what gets translated.
        val preWritten = texts.indices.map { i -> afterWhole[i] ?: texts[i] }
        val needEngine = texts.indices.filter { afterWhole[it] == null }

        var engineId = config.engineId
        var unfinished = emptyList<Int>()
        val out = preWritten.toMutableList()
        if (needEngine.isNotEmpty()) {
            val translator = LineTranslator(engines(config), config.engineId) { diagnostic ->
                android.util.Log.i("MangaScribe", diagnostic)
            }
            val result = translator.translate(
                needEngine.map { preWritten[it] },
                if (sourceLanguage == target) "auto" else sourceLanguage,
                target,
                deadEngines.toList(),
            )
            engineId = result.engineId
            needEngine.forEachIndexed { i, original ->
                val value = result.lines.getOrNull(i)?.takeIf { it.isNotBlank() }
                if (value != null) {
                    // The engine answered, but may have left Latin words in the line. For a
                    // Kurdish target those are written in Kurdish letters here, once, before the
                    // text is stored — so the guarantee holds in the database too, not only on
                    // screen.
                    out[original] = if (Languages.of(target).rtl) {
                        // Glossary first (so a known term keeps one spelling), then the rescue for
                        // anything the engine left in Latin.
                        val withTerms = glossary.rewriteTermsInside(value).text
                        app.mangascribe.core.KurdishTransliteration.rescueMixed(withTerms)
                    } else {
                        value
                    }
                }
            }
            // A line that never came back fully translated keeps its source text: the renderer
            // then shows the original art instead of a half-English bubble.
            unfinished = result.unfinished
            // Every verified pair goes into the translation memory: a retry or the next chapter
            // is then a local read, not a request.
            runCatching {
                warmCache(
                    engineId, sourceLanguage, target,
                    needEngine.mapIndexed { i, original -> texts[original] to out[original] }
                        .toMap()
                        // A transliteration is a failure wearing a Kurdish costume. Caching it
                        // meant that once a page failed, every retranslate of that page replayed
                        // the same meaningless line from the translation memory — the page could
                        // never recover, and fixing the translator changed nothing on screen.
                        .filterValues { value -> value.isNotBlank() }
                        .filterValues { value ->
                            !KurdishTransliteration.isTransliterated(value)
                        },
                )
            }
        }
        return LineTranslator.Result(out, engineId, unfinished)
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
            val sources = existing.map { it.sourceText.replace("\n", " ") }
            val translated = translateLines(sources, book.sourceLanguage, targetLanguage, config)
            val updated = existing.mapIndexed { i, balloon ->
                balloon.copy(translatedText = translated.lines.getOrElse(i) { balloon.translatedText })
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
        val balloons = BalloonCodec.decode(page.blocksJson)
        val target = languagesFor(page)
        return if (target.second.rtl) refreshStaleBalloons(pageId, balloons, target.first, target.second.code)
        else balloons
    }

    /**
     * A page translated by an older build keeps its old, half-English text in the database.
     * Those balloons were saved once and never looked at again — which is why fixing the
     * translator changed nothing on the phone: the same stored English was drawn.
     *
     * Here, on read, any stored line that still fails the no-leftover test is put through the
     * current pipeline together with its source. A transliterated line and a clean Kurdish line
     * are left alone, so this costs nothing on a healthy page and repairs an old one once.
     */
    private suspend fun refreshStaleBalloons(
        pageId: Long,
        balloons: List<Balloon>,
        sourceLanguage: String,
        targetLanguage: String,
    ): List<Balloon> {
        val stale = balloons.withIndex().filter { (_, balloon) ->
            if (balloon.sourceText.isBlank() || balloon.translatedText.isBlank()) return@filter false
            // A transliterated line is stale by definition: it says "this could not be
            // translated". Earlier builds stored those, so they are repaired on read even though
            // they contain no Latin letters and pass every other test.
            if (KurdishTransliteration.isTransliterated(balloon.translatedText)) return@filter true
            TranslatedTextCleanup.hasLeftoverSource(
                balloon.translatedText,
                balloon.sourceText,
                targetIsRtl = true,
            )
        }
        if (stale.isEmpty()) return balloons

        // Step 1, offline: any Latin words inside an otherwise Kurdish line become Kurdish
        // letters. This is instant, needs no network, and fixes exactly the reported bubble
        // ("ئەسپێکت: temple slave"). Nothing here can hang the reader.
        val updated = balloons.toMutableList()
        val stillLatin = mutableListOf<Int>()
        stale.forEach { entry ->
            val rescued = app.mangascribe.core.KurdishTransliteration.rescueMixed(
                entry.value.translatedText,
            )
            updated[entry.index] = entry.value.copy(translatedText = rescued)
            // Two kinds of miss ask the engines for a real translation:
            //  - a line that was entirely Latin (nothing to keep), and
            //  - a line that was transliterated: Kurdish letters, no meaning.
            val before = entry.value.translatedText
            val wasTransliterated = KurdishTransliteration.isTransliterated(before)
            if (wasTransliterated ||
                (KurdishTransliteration.hasLatinLetters(before) &&
                    before.none { it in '\u0600'..'\u06FF' })
            ) {
                stillLatin += entry.index
            }
        }
        if (stillLatin.isEmpty()) {
            saveBalloons(pageId, updated.map { it.copy() })
            return updated
        }

        // Step 2, on the network: only the lines with no Kurdish at all. Wrapped in a short
        // budget so opening an old page can never hang on an unreachable endpoint.
        val repaired = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                val config = settings.current()
                translateLines(
                    stillLatin.map { updated[it].sourceText },
                    sourceLanguage,
                    targetLanguage,
                    config,
                ).lines
            }
        }.getOrNull()
        if (repaired != null) {
            stillLatin.forEachIndexed { position, index ->
                val fresh = repaired.getOrNull(position)?.takeIf { it.isNotBlank() }
                if (fresh != null) updated[index] = updated[index].copy(translatedText = fresh)
            }
        }
        // Same ids, same geometry: only the text is replaced.
        saveBalloons(pageId, updated.map { it.copy() })
        return updated
    }

    /** Source and target for a page, following the book and the current settings. */
    private suspend fun languagesFor(page: PageEntity): Pair<String, app.mangascribe.core.Language> {
        val book = dao.find(page.bookId)
        val config = settings.current()
        val source = book?.sourceLanguage?.takeIf { it.isNotBlank() } ?: config.sourceLanguage
        val target = book?.targetLanguage?.takeIf { it.isNotBlank() } ?: config.targetLanguage
        return source to Languages.of(target)
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
        HostHealth.reset()
        KeylessTranslateTranslator.resetHosts()
    }

    /** One cache for the whole app: a line translated on page 3 is free on page 90. */
    private val translationCache by lazy { TranslationCache(context) }

    private fun engines(config: AppSettings): List<Translator> = buildList {
        // The keyless rotation first: several public endpoints, so a rate-limited one does not
        // end the page. Google (single endpoint) stays as a separate, fast first choice when the
        // user picked it explicitly.
        add(KeylessTranslateTranslator(http))
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

    companion object {
        /**
         * How long one page may spend in the engines before the reader is told something is
         * wrong. Generous — a chapter of status boxes with retries can take a while — but the
         * app must never sit on "Reading the page" forever.
         */
        const val PAGE_TRANSLATION_BUDGET_MS = 90_000L
    }
}

object ExportProvider {
    fun uriFor(context: Context, file: File): Uri =
        androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
}
