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
            Text(
                "Free engines need no account. A model endpoint gives the best dialogue and " +
                    "keeps each balloon separate.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showKeys = !showKeys }) {
                Icon(Icons.Filled.Lock, contentDescription = null)
                Text(if (showKeys) "  Hide keys" else "  Set up a model or your own server")
            }
            if (showKeys) {
                Text(
                    "Keys are stored on this device only and are excluded from cloud backups.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.llmBaseUrl,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(llmBaseUrl = value) } },
                    label = { Text("Model endpoint (OpenAI-compatible)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.llmModel,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(llmModel = value) } },
                    label = { Text("Model name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.llmApiKey,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(llmApiKey = value) } },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.libreEndpoint,
                    onValueChange = { value -> viewModel.updateSettings { it.copy(libreEndpoint = value) } },
                    label = { Text("LibreTranslate server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineRow(settings: AppSettings, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val engines = listOf(
        "mymemory" to "MyMemory — free, no key",
        "libretranslate" to "LibreTranslate — your own server",
        "openai-compatible" to "Model endpoint — best dialogue",
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
                selected = settings.engineId == "mymemory",
                onClick = { onSelect("mymemory") },
                label = { Text("Free") },
            )
            FilterChip(
                selected = settings.engineId == "openai-compatible",
                onClick = { onSelect("openai-compatible") },
                label = { Text("Best") },
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
