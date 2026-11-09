package app.mangascribe.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mangascribe.data.Balloon
import app.mangascribe.data.PageEntity
import app.mangascribe.ui.components.TranslatedPage
import app.mangascribe.vm.LibraryViewModel

/**
 * The reader. Vertical strip reading by default because most translated comics are webtoons,
 * with one page per screen when the source is a paginated manga: the page rows decide, not a
 * setting the user has to find.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    startPage: Int,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val pages by viewModel.pages(bookId).collectAsStateWithLifecycle()
    val book by viewModel.book(bookId).collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val translating by viewModel.translating.collectAsStateWithLifecycle()

    val balloonsByPage = remember { mutableStateMapOf<Long, List<Balloon>>() }
    var showOriginal by remember { mutableStateOf(false) }
    var editingPage by remember { mutableStateOf<PageEntity?>(null) }
    var menu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(pages.size) {
        if (pages.isNotEmpty()) listState.scrollToItem(startPage.coerceIn(0, pages.lastIndex))
    }
    LaunchedEffect(listState, pages) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val page = pages.getOrNull(index) ?: return@collect
            viewModel.setLastPage(bookId, page.index)
        }
    }
    // Load stored translations once per page.
    LaunchedEffect(pages) {
        pages.filter { it.status == PageEntity.Status.DONE && balloonsByPage[it.id] == null }
            .forEach { page -> viewModel.loadBalloons(page.id) { balloonsByPage[page.id] = it } }
    }

    val currentPage = pages.getOrNull(listState.firstVisibleItemIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            book?.title ?: "Reading",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${listState.firstVisibleItemIndex + 1} / ${pages.size} · " +
                                "${settings.source.nativeName} → ${settings.target.nativeName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MenuBook, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (showOriginal) "Show translation" else "Show original text") },
                            onClick = { menu = false; showOriginal = !showOriginal },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit this page's text") },
                            onClick = { menu = false; editingPage = currentPage },
                        )
                        DropdownMenuItem(
                            text = { Text("Translate whole book") },
                            onClick = { menu = false; viewModel.translateWholeBook(bookId) },
                        )
                        DropdownMenuItem(
                            text = { Text("Share this page") },
                            onClick = {
                                menu = false
                                currentPage?.let { page ->
                                    viewModel.exportPage(page.id) { uri ->
                                        uri?.let { viewModel.shareFile(context, it) }
                                    }
                                }
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            if (currentPage != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.translatePage(bookId, currentPage.index) { _, _ ->
                            viewModel.loadBalloons(currentPage.id) { balloonsByPage[currentPage.id] = it }
                        }
                    },
                    icon = {
                        if (translating != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Filled.Translate, contentDescription = null)
                        }
                    },
                    text = {
                        Text(
                            when (currentPage.status) {
                                PageEntity.Status.DONE -> "Translate again"
                                PageEntity.Status.FAILED -> "Retry"
                                PageEntity.Status.RUNNING -> "Reading…"
                                else -> "Translate page"
                            }
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                )
            }
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No pages in this book")
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(pages, key = { it.id }) { page ->
                val balloons = balloonsByPage[page.id].orEmpty()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(pageHeight(page))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    TranslatedPage(
                        imagePath = page.imageUri,
                        balloons = balloons,
                        settings = settings,
                        showOriginal = showOriginal,
                        modifier = Modifier.fillMaxSize(),
                    )
                    when (page.status) {
                        PageEntity.Status.NONE -> StatusChip(
                            "Tap the button to translate this page",
                            Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        )
                        PageEntity.Status.RUNNING -> StatusChip(
                            "Reading the page…",
                            Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        )
                        PageEntity.Status.FAILED -> StatusChip(
                            "Could not translate: ${page.error ?: "unknown error"}",
                            Modifier.align(Alignment.BottomCenter).padding(12.dp),
                        )
                        PageEntity.Status.DONE -> if (balloons.isEmpty()) {
                            StatusChip(
                                "No text found on this page",
                                Modifier.align(Alignment.BottomCenter).padding(12.dp),
                            )
                        }
                    }
                }
            }
            item { Box(Modifier.height(96.dp)) }
        }
    }

    editingPage?.let { page ->
        BalloonEditor(
            page = page,
            balloons = balloonsByPage[page.id].orEmpty(),
            onDismiss = { editingPage = null },
            onSave = { updated ->
                balloonsByPage[page.id] = updated
                viewModel.saveBalloons(page.id, updated)
                editingPage = null
            },
        )
    }
}

/** Webtoon strips read as long columns; a single panel reads at its own aspect ratio. */
private fun pageHeight(page: PageEntity): androidx.compose.ui.unit.Dp {
    val ratio = if (page.width > 0 && page.height > 0) page.height.toFloat() / page.width else 1.4f
    return (420f * ratio.coerceIn(0.35f, 3.2f)).dp
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Edit Mode. One row per balloon: retype the translation, hide it, or reset it to the OCR
 * text. Edits are page-local so a mistyped line never rewrites the chapter.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalloonEditor(
    page: PageEntity,
    balloons: List<Balloon>,
    onDismiss: () -> Unit,
    onSave: (List<Balloon>) -> Unit,
) {
    val working = remember(balloons) { balloons.map { it.copy() }.toMutableList() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Edit page ${page.index + 1}", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { onSave(working) }) { Text("Save") }
            }
            if (working.isEmpty()) {
                Text(
                    "Nothing has been read on this page yet. Translate it first.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            }
            LazyColumn(Modifier.height(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(working.size) { index ->
                    val balloon = working[index]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Balloon ${index + 1} · ${balloon.sourceText.take(24)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (balloon.hidden) "hidden" else "shown",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Switch(
                                checked = !balloon.hidden,
                                onCheckedChange = { visible ->
                                    working[index] = balloon.copy(hidden = !visible)
                                },
                            )
                        }
                        OutlinedTextField(
                            value = balloon.translatedText,
                            onValueChange = { working[index] = balloon.copy(translatedText = it) },
                            label = { Text("Translation") },
                            minLines = 1,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                working[index] = balloon.copy(translatedText = balloon.sourceText)
                            }) { Text("Use original") }
                            TextButton(onClick = {
                                working[index] = balloon.copy(offsetY = balloon.offsetY - 8f)
                            }) { Text("Up") }
                            TextButton(onClick = {
                                working[index] = balloon.copy(offsetY = balloon.offsetY + 8f)
                            }) { Text("Down") }
                            TextButton(onClick = {
                                working[index] = balloon.copy(
                                    scale = (balloon.scale + 0.1f).coerceAtMost(2f)
                                )
                            }) { Text("Bigger") }
                        }
                    }
                }
            }
        }
    }
}
