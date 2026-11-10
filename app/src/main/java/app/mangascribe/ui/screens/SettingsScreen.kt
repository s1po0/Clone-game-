package app.mangascribe.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import app.mangascribe.BuildConfig
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mangascribe.core.Language
import app.mangascribe.data.AppSettings
import app.mangascribe.vm.LibraryViewModel

/**
 * Settings. The first screen a new user needs is the language pair; everything else is
 * grouped under it, with the cloud keys folded away so the app works with no account at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: LibraryViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall)

        Section("Languages") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguagePicker(
                    label = "Read from",
                    selected = settings.sourceLanguage,
                    onSelected = { code -> viewModel.updateSettings { it.copy(sourceLanguage = code) } },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.swapLanguages() }) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap languages")
                }
                LanguagePicker(
                    label = "Translate to",
                    selected = settings.targetLanguage,
                    onSelected = { code -> viewModel.updateSettings { it.copy(targetLanguage = code) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Kurdish (Sorani), Arabic and Persian are right-to-left and are typeset that way.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Reading the page") {
            ToggleRow(
                "Clean up the original lettering",
                "Masks the source text with the bubble's own paper colour before drawing the translation.",
                settings.maskOriginal,
            ) { value -> viewModel.updateSettings { it.copy(maskOriginal = value) } }
            ToggleRow(
                "Vertical typesetting for Japanese",
                "Writes the translation in right-to-left columns when the source bubble is a column.",
                settings.verticalTypesetting,
            ) { value -> viewModel.updateSettings { it.copy(verticalTypesetting = value) } }
            ToggleRow(
                "Outline the text",
                "Adds a white halo, for pages with busy backgrounds.",
                settings.outlineText,
            ) { value -> viewModel.updateSettings { it.copy(outlineText = value) } }
            SliderRow(
                label = "Text size",
                value = settings.fontScale,
                range = 0.7f..1.6f,
                onChange = { value -> viewModel.updateSettings { it.copy(fontScale = value) } },
            )
        }

        Section("Reading the words") {
            ToggleRow(
                "Strip furigana",
                "Removes the small phonetic kana above kanji before translating — the most common " +
                    "cause of garbled Japanese pages.",
                settings.stripFurigana,
            ) { value -> viewModel.updateSettings { it.copy(stripFurigana = value) } }
            ToggleRow(
                "Join lines into balloons",
                "Groups the OCR's individual lines into one balloon, so a sentence is translated " +
                    "as a sentence.",
                settings.mergeBlocks,
            ) { value -> viewModel.updateSettings { it.copy(mergeBlocks = value) } }
            ToggleRow(
                "Translate a page when it opens",
                "Skips the tap on pages that have not been translated yet. Uses network per page.",
                settings.autoTranslateOnOpen,
            ) { value -> viewModel.updateSettings { it.copy(autoTranslateOnOpen = value) } }
        }

        Section("Translation engine") {
            KurdishCoverage(settings, viewModel)
            Text(
                "Google Translate, and nothing else. It is free, needs no key, covers every " +
                    "language in the app, and is the only free route into Kurdish Sorani. The " +
                    "app calls three Google endpoints in turn, so a rate limit slows a page " +
                    "down instead of failing it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Backup and moving to a new build") {
            Text(
                "A backup holds your library, the imported pages, every translation, and your " +
                    "settings. It does not hold API keys — those are encrypted to this phone, " +
                    "use Export keys in the engine section for those.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val backupPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
            ) { uri -> uri?.let { viewModel.importLibrary(it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.exportLibrary { uri ->
                        uri?.let { viewModel.shareFile(context, it, "application/zip") }
                    }
                }) { Text("Back up library") }
                OutlinedButton(onClick = { backupPicker.launch(arrayOf("application/zip")) }) {
                    Text("Restore backup")
                }
            }
            if (viewModel.legacyInstallPresent()) {
                Text(
                    "An older MangaScribe build is still installed as a separate app (the " +
                        "pre-update version). Back up here, install this build, then restore — " +
                        "or uninstall the old one once you are happy.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Section("Privacy") {
            Text(
                "Recognition happens on the phone. Only the recognised text is sent to the " +
                    "translator you chose — never the images. With a free engine and no key you can " +
                    "translate while offline for a page that is already done.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Section("Translation memory") {
            Text(
                "Every finished line is kept in a local database. A line is translated once — "
                    + "re-reading a chapter, retrying a page, or opening the next chapter costs no "
                    + "requests for anything already seen. This is also why a chapter works with "
                    + "the internet off after the first pass.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var cacheCount by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(Unit) { cacheCount = viewModel.cacheSize() }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (cacheCount == null) "Reading…" else "$cacheCount lines kept",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { viewModel.clearCache(); cacheCount = 0 }) {
                    Text("Clear")
                }
            }
        }

        // "Did the update install?" should be answerable without guessing: the build number is
        // the workflow run number, so it can be compared with the release page directly.
        Section("This build") {
            val context = LocalContext.current
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "—"
            }
            Text(
                "MangaScribe  ·  build ${BuildConfig.VERSION_CODE}  ·  $versionName",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "The build number is the CI run number and only ever increases. If a release " +
                    "page says apk-40 and this screen says build 40, the update installed. " +
                    "Settings, library and saved keys carry over — they are not touched by an " +
                    "update.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "MangaScribe · built for readers, not for scanners",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * The Kurdish question, answered honestly. Sorani is the language this app was asked for, and
 * the choice of engine decides whether it works at all — so the screen says so before the
 * user finds out from a failed translation.
 */
@Composable
private fun KurdishCoverage(settings: AppSettings, viewModel: LibraryViewModel) {
    val hasKey = settings.llmApiKey.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("کوردیی سۆرانی", style = MaterialTheme.typography.titleMedium)
        Text(
            "Ready: Sorani goes through Google Translate, with no key and nothing to set up. " +
                "Balloons are typeset right to left and keep the Kurdish letters (پ ژ گ چ ڤ ۆ ێ ڕ ڵ ە) " +
                "instead of being transliterated into Latin.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.targetLanguage == "ckb",
                onClick = { viewModel.useKurdish() },
                label = { Text("Target: Sorani") },
            )
        }
        if (hasKey) {
            // A key saved by an older build does nothing now; say so instead of silently
            // ignoring it, and let the reader wipe it.
            Text(
                "A model key from an older build is still stored on this phone. It is not used " +
                    "any more — Google is the engine — and it can be deleted from Secrets.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
