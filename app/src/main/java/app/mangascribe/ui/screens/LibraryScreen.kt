package app.mangascribe.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mangascribe.data.BookEntity
import app.mangascribe.ui.components.CoverImage
import app.mangascribe.vm.LibraryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Library: covers in a two-column grid, the current source→target pair as chips at the top,
 * and the two import doors (image picker, archive picker). Long-press opens the actions.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (Long, Int) -> Unit,
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var onlyFavorites by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var pendingRename by remember { mutableStateOf<BookEntity?>(null) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> -> viewModel.importUris(uris) }

    val pickArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "Comic"
            viewModel.importDocument(uri, name)
        }
    }

    val shown = (if (onlyFavorites) favorites else books)
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { pickImages.launch("image/*") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Import pages") },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "MangaScribe",
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "Manga, manhwa and comics — read in your language.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { viewModel.swapLanguages() },
                            label = {
                                Text(
                                    "${settings.source.nativeName} → ${settings.target.nativeName}",
                                )
                            },
                        )
                        AssistChip(
                            onClick = { pickArchive.launch(arrayOf("*/*")) },
                            label = { Text("Open .cbz / .zip") },
                        )
                    }
                    // The one-tap routes that matter: Kurdish Sorani first, then its neighbours.
                    Text(
                        "Read in",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.targetLanguage == "ckb",
                            onClick = { viewModel.useKurdish() },
                            label = { Text("کوردیی سۆرانی") },
                            leadingIcon = if (settings.targetLanguage == "ckb") {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null,
                        )
                        FilterChip(
                            selected = settings.targetLanguage == "tr",
                            onClick = { viewModel.updateSettings { it.copy(targetLanguage = "tr") } },
                            label = { Text("Türkçe") },
                        )
                        FilterChip(
                            selected = settings.targetLanguage == "ar",
                            onClick = { viewModel.updateSettings { it.copy(targetLanguage = "ar") } },
                            label = { Text("العربية") },
                        )
                        FilterChip(
                            selected = settings.targetLanguage == "en",
                            onClick = { viewModel.updateSettings { it.copy(targetLanguage = "en") } },
                            label = { Text("English") },
                        )
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search the library") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !onlyFavorites,
                            onClick = { onlyFavorites = false },
                            label = { Text("All ${books.size}") },
                        )
                        FilterChip(
                            selected = onlyFavorites,
                            onClick = { onlyFavorites = true },
                            label = { Text("Favorites ${favorites.size}") },
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    EmptyLibrary(
                        onPickImages = { pickImages.launch("image/*") },
                        onPickArchive = { pickArchive.launch(arrayOf("*/*")) },
                    )
                }
            }

            items(shown, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    onClick = { onOpenBook(book.id, book.lastPageIndex) },
                    onFavorite = { viewModel.toggleFavorite(book.id) },
                    onDelete = { pendingDelete = book },
                    onTranslate = { viewModel.translateWholeBook(book.id) },
                    onRename = { pendingRename = book },
                )
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove “${book.title}”?") },
            text = { Text("The imported pages and their translations are deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBook(book.id)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Keep") } },
        )
    }

    pendingRename?.let { book ->
        var draft by remember(book.id) { mutableStateOf(book.title) }
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Title") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        viewModel.renameBook(book.id, draft.trim())
                        pendingRename = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { pendingRename = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: BookEntity,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onTranslate: () -> Unit,
    onRename: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Box {
            CoverImage(
                path = book.coverPath,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
            )
            if (book.isFavorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50))
                        .padding(4.dp)
                        .size(18.dp),
                )
            }
            if (book.origin == "demo") {
                AssistChip(
                    onClick = onClick,
                    label = { Text("Try me", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }
            Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (book.isFavorite) "Remove from favorites" else "Add to favorites") },
                        leadingIcon = {
                            Icon(
                                if (book.isFavorite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
                                contentDescription = null,
                            )
                        },
                        onClick = { menu = false; onFavorite() },
                    )
                    DropdownMenuItem(
                        text = { Text("Translate whole book") },
                        onClick = { menu = false; onTranslate() },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${book.pageCount} page${if (book.pageCount == 1) "" else "s"} · " +
                    "${app.mangascribe.core.Languages.of(book.sourceLanguage).code} → " +
                    "${app.mangascribe.core.Languages.of(book.targetLanguage).code}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(book.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyLibrary(onPickImages: () -> Unit, onPickArchive: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.AutoStories,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
        Text(
            "Import pages from your gallery, open a .cbz or .zip, or share an image into the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(
                onClick = onPickImages,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) { Text("Pick images", Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
            Card(
                onClick = onPickArchive,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) { Text("Open archive", Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) }
        }
    }
}
