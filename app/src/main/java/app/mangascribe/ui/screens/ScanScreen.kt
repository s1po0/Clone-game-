package app.mangascribe.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mangascribe.core.ReadingLayout
import app.mangascribe.core.ReadingMode
import app.mangascribe.data.Balloon
import app.mangascribe.data.PageEntity
import app.mangascribe.util.Dates
import app.mangascribe.ui.components.TranslatedPage
import app.mangascribe.vm.LibraryViewModel

/**
 * Screen translation, the "overlay" door: take a screenshot of any app or site, drop it here,
 * and read the page back in the target language. It is the same pipeline as a library page,
 * just with a one-page book behind it so history is kept.
 */
@Composable
fun ScanScreen(viewModel: LibraryViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var bookId by remember { mutableStateOf<Long?>(null) }
    var page by remember { mutableStateOf<PageEntity?>(null) }
    var balloons by remember { mutableStateOf<List<Balloon>>(emptyList()) }
    var engine by remember { mutableStateOf<String?>(null) }

    var pending by remember { mutableStateOf(false) }
    // The batch the import produced: every page of it, translated and drawn stacked.
    var batch by remember { mutableStateOf<List<PageEntity>>(emptyList()) }
    val balloonsByPage = remember { androidx.compose.runtime.mutableStateMapOf<Long, List<Balloon>>() }
    val screenWidthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.toFloat()
    // The book that was newest when the picker opened: anything else is the new import.
    var previousNewest by remember { mutableStateOf<Long?>(null) }
    val books by viewModel.books.collectAsStateWithLifecycle()

    // One screenshot or twenty pages of a chapter: the picker allows multiple selection, and
    // the import sorts them by name so the order the user tapped is not the reading order.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        previousNewest = books.maxByOrNull { it.createdAt }?.id
        page = null
        balloons = emptyList()
        engine = null
        viewModel.importUris(
            uris,
            title = if (uris.size == 1) "Screenshot — ${Dates.shortNow()}" else "Screens — ${Dates.shortNow()}",
        )
        // The import is async; the books flow tells us when the new one lands.
        pending = true
    }

    // Watches for the book the import created, then translates its page straight away.
    LaunchedEffect(books, pending) {
        if (!pending) return@LaunchedEffect
        val newest = books.maxByOrNull { it.createdAt } ?: return@LaunchedEffect
        if (newest.id == previousNewest || newest.pageCount == 0) return@LaunchedEffect
        bookId = newest.id
        pending = false
        // Multi-page import: translate the whole batch, not just the first page.
        if (newest.pageCount > 1) viewModel.translateWholeBook(newest.id)
        else viewModel.translatePage(newest.id, 0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Scan a screen", style = MaterialTheme.typography.displaySmall)
        Text(
            "Take a screenshot of any app, game or web page and import it here. The text is " +
                "read, translated, and laid back onto the picture — nothing is uploaded except " +
                "the text the engine needs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val advice = app.mangascribe.translate.EngineCatalog.kurdishAdvice(settings.targetLanguage)
        if (advice != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Text(
                    advice,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Translating ${settings.source.displayName} → ${settings.target.displayName}",
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "Change the pair in Settings. Japanese gets furigana cleanup and vertical " +
                        "typesetting automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { pick.launch("image/*") }) {
                Icon(Icons.Filled.Image, contentDescription = null)
                Text("  Pick screenshots")
            }
            OutlinedButton(
                onClick = {
                    val text = balloons.joinToString("\n") { it.translatedText }
                    if (text.isBlank()) viewModel.showToast("Translate something first")
                    else {
                        clipboard.setText(AnnotatedString(text))
                        viewModel.showToast("Copied the translation")
                    }
                },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null)
                Text("  Copy text")
            }
        }

        if (batch.isNotEmpty()) {
            // Every imported page, stacked and translated in place. A fixed 520 dp preview of
            // page 1 used to be the whole screen, which hid the rest of the batch.
            batch.forEach { row ->
                Card {
                    TranslatedPage(
                        imagePath = row.imageUri,
                        balloons = balloonsByPage[row.id].orEmpty(),
                        settings = settings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(
                                ReadingLayout.scrollHeightDp(
                                    row.width, row.height, screenWidthDp, 600f, ReadingMode.FIT_WIDTH,
                                ).dp
                            ),
                    )
                }
                if (row.status != PageEntity.Status.DONE) {
                    Text(
                        when (row.status) {
                            PageEntity.Status.NONE -> "Page ${row.index + 1} · waiting"
                            PageEntity.Status.RUNNING -> "Page ${row.index + 1} · reading…"
                            PageEntity.Status.FAILED -> "Page ${row.index + 1} · ${row.error ?: "failed"}"
                            else -> ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    viewModel.retranslatePage(page!!.id, settings.targetLanguage) { balloons = it }
                }) { Icon(Icons.Filled.Translate, contentDescription = null); Text("  Translate again") }
                OutlinedButton(onClick = {
                    viewModel.exportPage(page!!.id) { uri ->
                        uri?.let { viewModel.shareFile(context, it) }
                    }
                }) { Icon(Icons.Filled.Share, contentDescription = null); Text("  Share result") }
            }
            if (engine != null) {
                Text("Engine: $engine", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (page?.status == PageEntity.Status.FAILED) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        page?.error ?: "The page could not be translated",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    TextButton(onClick = {
                        bookId?.let { viewModel.translatePage(it, 0) }
                    }) { Text("Try again") }
                }
            }
        } else if (page?.status == PageEntity.Status.DONE) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text("No text was found in that picture. Try a sharper screenshot.")
            }
        }

        Text(
            "How to screenshot: press Power + Volume Down. The picture lands in Photos; pick it here.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // The whole batch of the newest book: rows first, balloons as each page finishes.
    val translating by viewModel.translating.collectAsStateWithLifecycle()
    LaunchedEffect(bookId, translating) {
        val id = bookId ?: return@LaunchedEffect
        viewModel.pagesSnapshot(id) { rows ->
            batch = rows
            rows.filter { it.status == PageEntity.Status.DONE }.forEach { row ->
                viewModel.loadBalloonsForPage(id, row.index) { loaded ->
                    balloonsByPage[row.id] = loaded
                    // the single-page summary below still points at the last completed page
                    if (loaded.isNotEmpty()) {
                        page = row
                        balloons = loaded
                    }
                }
            }
        }
    }

    // Re-read the page row and its balloons when translation finishes.
    LaunchedEffect(translating, bookId) {
        val id = bookId ?: return@LaunchedEffect
        if (translating != null) return@LaunchedEffect
        viewModel.pageAt(id, 0) { loadedPage ->
            if (loadedPage?.status == PageEntity.Status.DONE) page = loadedPage
        }
        viewModel.loadBalloonsForPage(id, 0) { loaded ->
            balloons = loaded
            if (loaded.isNotEmpty()) engine = "on-device OCR + ${settings.engineId}"
        }
    }
}
