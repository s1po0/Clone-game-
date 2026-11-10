package app.mangascribe.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.mangascribe.MangaScribeApp
import app.mangascribe.core.Languages
import app.mangascribe.data.AppSettings
import app.mangascribe.data.Balloon
import app.mangascribe.data.BookEntity
import app.mangascribe.data.PageEntity
import app.mangascribe.data.PageTranslation
import app.mangascribe.data.LibraryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Toast(val message: String, val id: Long = System.nanoTime())

data class ImportProgress(val running: Boolean, val label: String = "")

/**
 * One view model for the library, the reader and settings: the screens are three views on the
 * same small graph, and splitting it would only add plumbing.
 */
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository: LibraryRepository = (app as MangaScribeApp).graph.repository

    val settings: StateFlow<AppSettings> = (app as MangaScribeApp).graph.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val books: StateFlow<List<BookEntity>> = repository.books()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val favorites: StateFlow<List<BookEntity>> = repository.favorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _toast = MutableStateFlow<Toast?>(null)
    val toast: StateFlow<Toast?> = _toast.asStateFlow()

    private val _importing = MutableStateFlow(ImportProgress(false))
    val importing: StateFlow<ImportProgress> = _importing.asStateFlow()

    private val _translating = MutableStateFlow<String?>(null)
    val translating: StateFlow<String?> = _translating.asStateFlow()

    private val _chapterProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val chapterProgress: StateFlow<Pair<Int, Int>?> = _chapterProgress.asStateFlow()

    private var chapterJob: Job? = null

    fun showToast(message: String) { _toast.value = Toast(message) }
    fun clearToast() { _toast.value = null }

    // ---- Settings -------------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { (getApplication() as MangaScribeApp).graph.settings.update(transform) }
    }

    /** The store behind settings, for the credential editor's direct writes. */
    private val settingsStore get() = (getApplication() as MangaScribeApp).graph.settings

    /**
     * Writes a credential and nothing else. Called on every keystroke from the settings
     * screen: the SecretStore writes synchronously, so the value is durable as soon as it is
     * typed, and the settings flow re-emits so the "Saved on this device" line is truthful.
     */
    fun setCredentials(
        llmApiKey: String? = null,
        libreApiKey: String? = null,
        myMemoryEmail: String? = null,
        llmBaseUrl: String? = null,
        llmModel: String? = null,
        libreEndpoint: String? = null,
    ) {
        viewModelScope.launch {
            val current = settings.value
            settingsStore.update {
                current.copy(
                    llmApiKey = llmApiKey ?: current.llmApiKey,
                    libreApiKey = libreApiKey ?: current.libreApiKey,
                    myMemoryEmail = myMemoryEmail ?: current.myMemoryEmail,
                    llmBaseUrl = llmBaseUrl ?: current.llmBaseUrl,
                    llmModel = llmModel ?: current.llmModel,
                    libreEndpoint = libreEndpoint ?: current.libreEndpoint,
                )
            }
        }
    }

    fun exportSecretBundle(): String? {
        val bundle = settingsStore.exportSecrets()
        if (bundle.isBlank()) {
            showToast("No key saved yet")
            return null
        }
        return bundle
    }

    fun importSecretBundle(bundle: String): Boolean = settingsStore.importSecrets(bundle)

    fun secretsProblem(): String? = settingsStore.secretsProblem()

    fun swapLanguages() = updateSettings {
        it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
    }

    /**
     * One tap into Kurdish Sorani. If no engine on this device can reach Kurdish yet, the
     * preference is still saved — the settings screen then says what is missing instead of
     * a translate button that would fail silently.
     */
    fun useKurdish() {
        updateSettings { it.copy(targetLanguage = "ckb") }
        val s = settings.value
        if (app.mangascribe.translate.EngineCatalog.capable("mymemory", s.sourceLanguage, "ckb")
                .isEmpty() && app.mangascribe.translate.EngineCatalog
                .capable(app.mangascribe.core.EngineCodes.LLM, s.sourceLanguage, "ckb").isEmpty()
        ) {
            showToast("Kurdish needs a model key — add one in Settings")
        }
    }

    /** Whether any configured engine can do the current pair, and what to say if not. */
    fun pairSupported(targetLanguage: String): Boolean =
        app.mangascribe.translate.EngineCatalog.capable(
            settings.value.engineId,
            settings.value.sourceLanguage,
            targetLanguage,
        ).isNotEmpty() || app.mangascribe.core.EngineCodes.isKurdish(targetLanguage).not()

    // ---- Import ---------------------------------------------------------------------------

    fun importUris(uris: List<Uri>, title: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _importing.value = ImportProgress(true, "Importing ${uris.size} page(s)")
            runCatching {
                val name = title ?: "Imported ${java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
                repository.importUris(name, uris, origin = "file")
            }.onSuccess {
                _toast.value = Toast("Imported ${uris.size} page(s)")
            }.onFailure {
                _toast.value = Toast(it.message ?: "Import failed")
            }
            _importing.value = ImportProgress(false)
        }
    }

    fun importDocument(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            _importing.value = ImportProgress(true, "Opening ${displayName ?: "file"}")
            runCatching {
                repository.importArchive(uri, displayName ?: "Comic")
            }.onSuccess {
                _toast.value = Toast("Imported ${displayName ?: "comic"}")
            }.onFailure {
                _toast.value = Toast(it.message ?: "That file could not be opened")
            }
            _importing.value = ImportProgress(false)
        }
    }

    fun deleteBook(id: Long) {
        viewModelScope.launch {
            repository.deleteBook(id)
            _toast.value = Toast("Removed from the library")
        }
    }

    fun renameBook(id: Long, title: String) {
        viewModelScope.launch { repository.renameBook(id, title) }
    }

    fun toggleFavorite(id: Long) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }

    // ---- Reading + translation -------------------------------------------------------------

    private val pagesFlows = mutableMapOf<Long, StateFlow<List<PageEntity>>>()
    private val bookFlows = mutableMapOf<Long, StateFlow<BookEntity?>>()

    fun pages(bookId: Long): StateFlow<List<PageEntity>> = pagesFlows.getOrPut(bookId) {
        repository.pages(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    fun book(bookId: Long): StateFlow<BookEntity?> = bookFlows.getOrPut(bookId) {
        repository.book(bookId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    fun translatePage(bookId: Long, index: Int, onDone: (Balloon?, String?) -> Unit = { _, _ -> }) {
        if (_translating.value != null) return
        viewModelScope.launch {
            _translating.value = "Reading the page"
            runCatching { repository.translatePage(bookId, index) }
                .onSuccess { result: PageTranslation ->
                    _translating.value = null
                    if (result.balloons.isEmpty()) {
                        _toast.value = Toast("No text found on this page")
                        onDone(null, null)
                    } else {
                        onDone(result.balloons.firstOrNull(), result.engine)
                    }
                }
                .onFailure {
                    _translating.value = null
                    _toast.value = Toast(it.message ?: "Translation failed")
                    onDone(null, null)
                }
        }
    }

    fun translateWholeBook(bookId: Long) {
        if (chapterJob?.isActive == true) return
        chapterJob = viewModelScope.launch {
            _chapterProgress.value = 0 to 0
            repository.translateBook(bookId) { done, total, failed ->
                _chapterProgress.value = done to total
                if (done == total) {
                    _toast.value = Toast(
                        if (failed == 0) "Chapter translated — $total pages"
                        else "Chapter finished: $failed page(s) failed"
                    )
                }
            }
            _chapterProgress.value = null
        }
    }

    fun cancelChapter() {
        chapterJob?.cancel()
        chapterJob = null
        _chapterProgress.value = null
    }

    fun retranslatePage(pageId: Long, target: String, onDone: (List<Balloon>) -> Unit) {
        viewModelScope.launch {
            _translating.value = "Translating again"
            runCatching { repository.retranslatePage(pageId, target) }
                .onSuccess { onDone(it) }
                .onFailure { _toast.value = Toast(it.message ?: "Retranslation failed") }
            _translating.value = null
        }
    }

    fun saveBalloons(pageId: Long, balloons: List<Balloon>) {
        viewModelScope.launch { repository.saveBalloons(pageId, balloons) }
    }

    fun loadBalloons(pageId: Long, onLoaded: (List<Balloon>) -> Unit) {
        viewModelScope.launch { onLoaded(repository.pageBalloons(pageId)) }
    }

    fun loadBalloonsForPage(bookId: Long, index: Int, onLoaded: (List<Balloon>) -> Unit) {
        viewModelScope.launch {
            val page = repository.pageAt(bookId, index)
            onLoaded(if (page == null) emptyList() else repository.pageBalloons(page.id))
        }
    }

    fun pageAt(bookId: Long, index: Int, onLoaded: (PageEntity?) -> Unit) {
        viewModelScope.launch { onLoaded(repository.pageAt(bookId, index)) }
    }

    /** Opens the system share sheet with a file the app just wrote. */
    fun shareFile(context: android.content.Context, uri: Uri) {
        runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share the translated page")
            )
        }.onFailure { _toast.value = Toast(it.message ?: "Sharing failed") }
    }

    fun exportPage(pageId: Long, onReady: (Uri?) -> Unit) {
        viewModelScope.launch {
            val uri = runCatching { repository.exportPage(pageId) }.getOrNull()
            if (uri == null) _toast.value = Toast("Translate the page before exporting it")
            onReady(uri)
        }
    }

    fun setLastPage(bookId: Long, index: Int) {
        viewModelScope.launch { repository.setLastPage(bookId, index) }
    }

    val languages = Languages.all
}
