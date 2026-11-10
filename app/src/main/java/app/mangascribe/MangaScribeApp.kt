package app.mangascribe

import android.app.Application
import android.content.Context
import androidx.room.Room
import app.mangascribe.data.LibraryRepository
import app.mangascribe.data.MangaScribeDatabase
import app.mangascribe.data.SettingsStore
import app.mangascribe.ocr.MlKitOcrEngine
import app.mangascribe.translate.Http
import app.mangascribe.translate.OkHttpHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Manual dependency graph. Two objects on purpose: the app is small, and a DI framework here
 * would be ceremony that hides where the OCR engine and the network live.
 */
class AppGraph(context: Context) {
    val database: MangaScribeDatabase = Room.databaseBuilder(
        context.applicationContext,
        MangaScribeDatabase::class.java,
        MangaScribeDatabase.NAME,
    ).fallbackToDestructiveMigration().build()

    val settings = SettingsStore(context.applicationContext)
    val http: Http = OkHttpHttp()
    val ocr = MlKitOcrEngine(context.applicationContext)
    val repository = LibraryRepository(
        context = context.applicationContext,
        dao = database.books(),
        settings = settings,
        http = http,
        ocr = ocr,
    )
}

class MangaScribeApp : Application() {
    lateinit var graph: AppGraph
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        scope.launch { ensureDemoContent() }
    }

    /**
     * First run gets a page to try the pipeline on, so the app never opens on an empty screen
     * with no way to see what it does. It is a drawn sample page, not a scraped one.
     */
    private suspend fun ensureDemoContent() {
        runCatching {
            val hasAny = graph.repository.books().first().isNotEmpty()
            if (hasAny) return@runCatching
            val file = app.mangascribe.util.DemoPage.writeSamplePage(this)
            graph.repository.importFiles(
                title = "Sample page — try it",
                files = listOf(file),
                origin = "demo",
                sourceLanguage = "ja",
                targetLanguage = "en",
            )
        }
    }
}
