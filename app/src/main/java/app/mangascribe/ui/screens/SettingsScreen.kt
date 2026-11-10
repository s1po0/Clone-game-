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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
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
    var showKeys by remember { mutableStateOf(false) }
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
            EngineRow(settings) { id -> viewModel.updateSettings { it.copy(engineId = id) } }
            KurdishCoverage(settings, viewModel)
            Text(
                "Google Translate is the default: no key, every language in the app, and the " +
                    "only free engine with Kurdish Sorani. A model endpoint is better for " +
                    "tone, names and jokes in long dialogue.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showKeys = !showKeys }) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text(if (showKeys) "  Hide keys" else "  Set up a model or your own server")
            }
            if (showKeys) {
                CredentialFields(viewModel, settings)
            }
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

        Spacer(Modifier.height(32.dp))
        Text(
            "MangaScribe 1.0 · built for readers, not for scanners",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Credential entry that survives being interrupted.
 *
 * A text field bound straight to an async store loses characters: every keystroke triggers a
 * write, the store's value arrives a frame later, and anything typed in between is reverted —
 * a pasted key ending up half there. So each field keeps its own draft while it has focus, is
 * committed on every change (debounced by the store, not by the UI), and is re-read from the
 * store only when the field is not being edited (for example after an import).
 */
@Composable
private fun CredentialFields(viewModel: LibraryViewModel, settings: AppSettings) {
    var modelKey by remember(settings.llmApiKey) { mutableStateOf(settings.llmApiKey) }
    var libreKey by remember(settings.libreApiKey) { mutableStateOf(settings.libreApiKey) }
    var baseUrl by remember(settings.llmBaseUrl) { mutableStateOf(settings.llmBaseUrl) }
    var modelName by remember(settings.llmModel) { mutableStateOf(settings.llmModel) }
    var libreUrl by remember(settings.libreEndpoint) { mutableStateOf(settings.libreEndpoint) }
    var reveal by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    val saved = settings.llmApiKey.isNotBlank() || settings.libreApiKey.isNotBlank()

    Text(
        "Keys are encrypted with the phone's Keystore and survive restarts, updates and " +
            "reboots. They are excluded from cloud backups, so nothing leaves the device — " +
            "use Export below to move them to another phone.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (saved) "Saved on this device" else "No key saved yet",
            style = MaterialTheme.typography.labelLarge,
            color = if (saved) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { reveal = !reveal }) {
            Text(if (reveal) "Hide" else "Show")
        }
    }

    OutlinedTextField(
        value = baseUrl,
        onValueChange = {
            baseUrl = it
            viewModel.setCredentials(llmBaseUrl = it)
        },
        label = { Text("Model endpoint (OpenAI-compatible)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = modelName,
        onValueChange = {
            modelName = it
            viewModel.setCredentials(llmModel = it)
        },
        label = { Text("Model name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = modelKey,
        onValueChange = {
            modelKey = it
            // Stored on every change, synchronously: paste, background the app, come back,
            // and the key is still in the field and still on disk.
            viewModel.setCredentials(llmApiKey = it)
        },
        label = { Text("API key") },
        placeholder = { Text("sk-…") },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None
        else PasswordVisualTransformation(),
        trailingIcon = {
            if (modelKey.isNotBlank()) {
                TextButton(onClick = {
                    modelKey = ""
                    viewModel.setCredentials(llmApiKey = "")
                }) { Text("Clear") }
            }
        },
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = libreUrl,
        onValueChange = {
            libreUrl = it
            viewModel.setCredentials(libreEndpoint = it)
        },
        label = { Text("LibreTranslate server") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = libreKey,
        onValueChange = {
            libreKey = it
            viewModel.setCredentials(libreApiKey = it)
        },
        label = { Text("LibreTranslate API key (optional)") },
        singleLine = true,
        visualTransformation = if (reveal) VisualTransformation.None
        else PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val bundle = viewModel.exportSecretBundle() ?: return@OutlinedButton
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("MangaScribe keys", bundle)
            )
            viewModel.showToast("Key bundle copied — paste it on the other phone")
        }) { Text("Export keys") }

        OutlinedButton(onClick = { showImport = !showImport }) {
            Text(if (showImport) "Cancel import" else "Import keys")
        }
    }
    if (showImport) {
        OutlinedTextField(
            value = importText,
            onValueChange = { importText = it },
            label = { Text("Paste the bundle from the other phone") },
            minLines = 2,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            enabled = importText.isNotBlank(),
            onClick = {
                if (viewModel.importSecretBundle(importText.trim())) {
                    importText = ""
                    showImport = false
                    viewModel.showToast("Keys imported")
                } else {
                    viewModel.showToast("That bundle could not be read")
                }
            },
        ) { Text("Import") }
    }
    viewModel.secretsProblem()?.let { problem ->
        Text(
            problem,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Divider()
            content()
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label · ${"%.2f".format(value)}×", style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePicker(
    label: String,
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val language = app.mangascribe.core.Languages.of(selected)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AssistChip(
            onClick = { open = true },
            label = { Text(language.nativeName) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            viewModelLanguages().forEach { lang ->
                DropdownMenuItem(
                    text = { Text("${lang.nativeName} — ${lang.displayName}") },
                    onClick = { open = false; onSelected(lang.code) },
                )
            }
        }
    }
}

private fun viewModelLanguages(): List<Language> = app.mangascribe.core.Languages.all

/**
 * The Kurdish question, answered honestly. Sorani is the language this app was asked for, and
 * the choice of engine decides whether it works at all — so the screen says so before the
 * user finds out from a failed translation.
 */
@Composable
private fun KurdishCoverage(settings: AppSettings, viewModel: LibraryViewModel) {
    val supported = app.mangascribe.translate.EngineCatalog.supports(
        app.mangascribe.core.EngineCodes.LLM,
        settings.sourceLanguage,
        "ckb",
    )
    val hasKey = settings.llmApiKey.isNotBlank()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("کوردیی سۆرانی", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                hasKey && supported ->
                    "Ready: your model endpoint translates into Sorani, and the page is typeset " +
                        "right to left with the Kurdish letters kept intact."
                hasKey ->
                    "Your model can translate, but this source language has no Kurdish route yet."
                else ->
                    "Not set up yet. Kurdish Sorani needs a model key: LibreTranslate has no " +
                        "Kurdish, and MyMemory only sometimes does. Without a key, pick Turkish " +
                        "as the target."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.targetLanguage == "ckb",
                onClick = { viewModel.useKurdish() },
                label = { Text("Target: Sorani") },
            )
            FilterChip(
                selected = settings.engineId == app.mangascribe.core.EngineCodes.LLM && hasKey,
                onClick = { viewModel.updateSettings { it.copy(engineId = "openai-compatible") } },
                label = { Text("Use the model") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineRow(settings: AppSettings, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val engines = listOf(
        app.mangascribe.core.EngineCodes.GOOGLE to "Google Translate — best quality, free, no key",
        "openai-compatible" to "Model endpoint — best for tone, needs a key",
        "libretranslate" to "LibreTranslate — your own server",
        "mymemory" to "MyMemory — free fallback, weaker quality",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(
            onClick = { open = true },
            label = {
                Text(engines.firstOrNull { it.first == settings.engineId }?.second ?: engines.first().second)
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = settings.engineId == app.mangascribe.core.EngineCodes.GOOGLE,
                onClick = { onSelect(app.mangascribe.core.EngineCodes.GOOGLE) },
                label = { Text("Google") },
            )
            FilterChip(
                selected = settings.engineId == "openai-compatible",
                onClick = { onSelect("openai-compatible") },
                label = { Text("Model") },
            )
        }
        Icon(Icons.Filled.ArrowForward, contentDescription = null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            engines.forEach { (id, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}
