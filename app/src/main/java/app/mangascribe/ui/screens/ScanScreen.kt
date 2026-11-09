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
    // The book that was newest when the picker opened: anything else is the new import.
    var previousNewest by remember { mutableStateOf<Long?>(null) }
    val books by viewModel.books.collectAsStateWithLifecycle()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        previousNewest = books.maxByOrNull { it.createdAt }?.id
        page = null
        balloons = emptyList()
        engine = null
        viewModel.importUris(listOf(uri), title = "Screenshot — ${Dates.shortNow()}")
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
        viewModel.translatePage(newest.id, 0)
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
                Text("  Pick a screenshot")
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

        if (page != null && balloons.isNotEmpty()) {
            Card {
                TranslatedPage(
                    imagePath = page?.imageUri,
                    balloons = balloons,
                    settings = settings,
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                )
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

    // Re-read the page row and its balloons when translation finishes.
    val translating by viewModel.translating.collectAsStateWithLifecycle()
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
