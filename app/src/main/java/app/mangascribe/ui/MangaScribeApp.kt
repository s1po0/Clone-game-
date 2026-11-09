package app.mangascribe.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mangascribe.ui.screens.LibraryScreen
import app.mangascribe.ui.screens.ReaderScreen
import app.mangascribe.ui.screens.ScanScreen
import app.mangascribe.ui.screens.SettingsScreen
import app.mangascribe.vm.LibraryViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("library", "Library", Icons.Filled.AutoStories),
    Tab("scan", "Scan", Icons.Filled.CenterFocusStrong),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun MangaScribeApp(
    sharedUris: List<Uri> = emptyList(),
    onSharedConsumed: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val toast by viewModel.toast.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val chapter by viewModel.chapterProgress.collectAsStateWithLifecycle()
    val working by viewModel.translating.collectAsStateWithLifecycle()

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it.message)
            viewModel.clearToast()
        }
    }

    // A share-sheet image becomes a one-page book and opens in the reader.
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            viewModel.importUris(sharedUris, title = "Shared page")
            onSharedConsumed()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    val showBar = route == null || route in tabs.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            NavHost(navController = navController, startDestination = "library") {
                composable("library") {
                    LibraryScreen(
                        viewModel = viewModel,
                        onOpenBook = { id, page -> navController.navigate("reader/$id/$page") },
                    )
                }
                composable("scan") { ScanScreen(viewModel = viewModel) }
                composable("settings") { SettingsScreen(viewModel = viewModel) }
                composable(
                    route = "reader/{bookId}/{page}",
                    arguments = listOf(
                        navArgument("bookId") { type = NavType.LongType },
                        navArgument("page") { type = NavType.IntType },
                    ),
                ) { entry ->
                    ReaderScreen(
                        bookId = entry.arguments?.getLong("bookId") ?: 0L,
                        startPage = entry.arguments?.getInt("page") ?: 0,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            val banner = when {
                importing.running -> importing.label
                working != null -> working
                else -> null
            }
            if (banner != null) {
                WorkingBanner(banner)
            }
        }
    }

    if (chapter != null) {
        ChapterSheet(
            done = chapter!!.first,
            total = chapter!!.second,
            onCancel = { viewModel.cancelChapter() },
        )
    }
}

@Composable
private fun WorkingBanner(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LinearProgressIndicator(Modifier.width(72.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ChapterSheet(done: Int, total: Int, onCancel: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (total == 0) "Starting chapter translation…" else "Translating page $done of $total",
                style = MaterialTheme.typography.titleMedium,
            )
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(onClick = onCancel) { Text("Stop after this page") }
        }
    }
}
