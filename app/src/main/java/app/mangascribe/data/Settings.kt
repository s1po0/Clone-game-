package app.mangascribe.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mangascribe.core.Languages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "mangascribe_settings")

data class AppSettings(
    val sourceLanguage: String = "ja",
    val targetLanguage: String = "en",
    val engineId: String = "mymemory",
    val stripFurigana: Boolean = true,
    val mergeBlocks: Boolean = true,
    val maskOriginal: Boolean = true,
    val verticalTypesetting: Boolean = true,
    val outlineText: Boolean = false,
    val fontScale: Float = 1f,
    val autoTranslateOnOpen: Boolean = false,
    val readerDirection: String = "auto", // auto | rtl | ltr
    val myMemoryEmail: String = "",
    val libreEndpoint: String = "https://translate.argosopentech.com",
    val libreApiKey: String = "",
    val llmBaseUrl: String = "https://api.openai.com/v1",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    val scanOnLaunch: Boolean = false,
) {
    val source: app.mangascribe.core.Language get() = Languages.of(sourceLanguage)
    val target: app.mangascribe.core.Language get() = Languages.of(targetLanguage)
}

class SettingsStore(private val context: Context) {

    private object Keys {
        val source = stringPreferencesKey("source_language")
        val target = stringPreferencesKey("target_language")
        val engine = stringPreferencesKey("engine")
        val furigana = booleanPreferencesKey("strip_furigana")
        val merge = booleanPreferencesKey("merge_blocks")
        val mask = booleanPreferencesKey("mask_original")
        val vertical = booleanPreferencesKey("vertical_typeset")
        val outline = booleanPreferencesKey("outline_text")
        val fontScale = floatPreferencesKey("font_scale")
        val autoTranslate = booleanPreferencesKey("auto_translate")
        val direction = stringPreferencesKey("reader_direction")
        val myMemoryEmail = stringPreferencesKey("mymemory_email")
        val libreEndpoint = stringPreferencesKey("libre_endpoint")
        val libreKey = stringPreferencesKey("libre_key")
        val llmBase = stringPreferencesKey("llm_base")
        val llmKey = stringPreferencesKey("llm_key")
        val llmModel = stringPreferencesKey("llm_model")
        val scanOnLaunch = booleanPreferencesKey("scan_on_launch")
        val migrations = intPreferencesKey("schema_version")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p) }

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sourceLanguage = p[Keys.source] ?: defaults.sourceLanguage,
            targetLanguage = p[Keys.target] ?: defaults.targetLanguage,
            engineId = p[Keys.engine] ?: defaults.engineId,
            stripFurigana = p[Keys.furigana] ?: defaults.stripFurigana,
            mergeBlocks = p[Keys.merge] ?: defaults.mergeBlocks,
            maskOriginal = p[Keys.mask] ?: defaults.maskOriginal,
            verticalTypesetting = p[Keys.vertical] ?: defaults.verticalTypesetting,
            outlineText = p[Keys.outline] ?: defaults.outlineText,
            fontScale = p[Keys.fontScale] ?: defaults.fontScale,
            autoTranslateOnOpen = p[Keys.autoTranslate] ?: defaults.autoTranslateOnOpen,
            readerDirection = p[Keys.direction] ?: defaults.readerDirection,
            myMemoryEmail = p[Keys.myMemoryEmail] ?: defaults.myMemoryEmail,
            libreEndpoint = p[Keys.libreEndpoint] ?: defaults.libreEndpoint,
            libreApiKey = p[Keys.libreKey] ?: defaults.libreApiKey,
            llmBaseUrl = p[Keys.llmBase] ?: defaults.llmBaseUrl,
            llmApiKey = p[Keys.llmKey] ?: defaults.llmApiKey,
            llmModel = p[Keys.llmModel] ?: defaults.llmModel,
            scanOnLaunch = p[Keys.scanOnLaunch] ?: defaults.scanOnLaunch,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(read(prefs))
            prefs[Keys.source] = updated.sourceLanguage
            prefs[Keys.target] = updated.targetLanguage
            prefs[Keys.engine] = updated.engineId
            prefs[Keys.furigana] = updated.stripFurigana
            prefs[Keys.merge] = updated.mergeBlocks
            prefs[Keys.mask] = updated.maskOriginal
            prefs[Keys.vertical] = updated.verticalTypesetting
            prefs[Keys.outline] = updated.outlineText
            prefs[Keys.fontScale] = updated.fontScale
            prefs[Keys.autoTranslate] = updated.autoTranslateOnOpen
            prefs[Keys.direction] = updated.readerDirection
            prefs[Keys.myMemoryEmail] = updated.myMemoryEmail
            prefs[Keys.libreEndpoint] = updated.libreEndpoint
            prefs[Keys.libreApiKey] = updated.libreApiKey
            prefs[Keys.llmBase] = updated.llmBaseUrl
            prefs[Keys.llmKey] = updated.llmApiKey
            prefs[Keys.llmModel] = updated.llmModel
            prefs[Keys.scanOnLaunch] = updated.scanOnLaunch
            prefs[Keys.migrations] = 1
        }
    }

    suspend fun current(): AppSettings = flow.first()
}
