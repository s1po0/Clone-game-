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
    val engineId: String = app.mangascribe.core.EngineCodes.GOOGLE,
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

/**
 * Settings, in two halves that fail differently.
 *
 * Ordinary preferences are in DataStore and are cheap to lose. **Credentials are not**: they
 * go through [SecretStore] (Keystore-encrypted, written synchronously), and the settings flow
 * is the merge of the two, so a key typed once is still there after a restart, an update or a
 * low-memory kill. DataStore's old key fields are migrated across on first run and then
 * deleted, because a plaintext key left behind in a preferences file defeats the point.
 */
class SettingsStore(private val context: Context, private val secrets: SecretStore = SecretStore(context)) {

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

    /** Preferences merged with the credentials on disk; one snapshot the UI can trust. */
    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p).with(loadSecrets()) }

    private fun loadSecrets(): Secrets = secrets.load()

    private fun AppSettings.with(s: Secrets): AppSettings = copy(
        llmApiKey = s.llmApiKey,
        libreApiKey = s.libreApiKey,
        myMemoryEmail = s.myMemoryEmail,
    )

    private fun read(p: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sourceLanguage = p[Keys.source] ?: defaults.sourceLanguage,
            targetLanguage = p[Keys.target] ?: defaults.targetLanguage,
            // An install that still points at MyMemory is moved to Google once: its crowd
            // translations were the reason pages read wrong. The choice stays editable.
            engineId = p[Keys.engine]?.let(::migrateEngine) ?: defaults.engineId,
            stripFurigana = p[Keys.furigana] ?: defaults.stripFurigana,
            mergeBlocks = p[Keys.merge] ?: defaults.mergeBlocks,
            maskOriginal = p[Keys.mask] ?: defaults.maskOriginal,
            verticalTypesetting = p[Keys.vertical] ?: defaults.verticalTypesetting,
            outlineText = p[Keys.outline] ?: defaults.outlineText,
            fontScale = p[Keys.fontScale] ?: defaults.fontScale,
            autoTranslateOnOpen = p[Keys.autoTranslate] ?: defaults.autoTranslateOnOpen,
            readerDirection = p[Keys.direction] ?: defaults.readerDirection,
            // Credentials come from SecretStore, merged in by `flow` and `update`.
            myMemoryEmail = defaults.myMemoryEmail,
            libreEndpoint = p[Keys.libreEndpoint] ?: defaults.libreEndpoint,
            libreApiKey = defaults.libreApiKey,
            llmBaseUrl = p[Keys.llmBase] ?: defaults.llmBaseUrl,
            llmApiKey = defaults.llmApiKey,
            llmModel = p[Keys.llmModel] ?: defaults.llmModel,
            scanOnLaunch = p[Keys.scanOnLaunch] ?: defaults.scanOnLaunch,
        )
    }

    private fun migrateEngine(stored: String): String =
        if (stored == "mymemory" || stored.isBlank()) app.mangascribe.core.EngineCodes.GOOGLE else stored

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val currentSecrets = loadSecrets()
        val before = read(currentPrefs()).with(currentSecrets)
        val updated = transform(before)
        val nextSecrets = Secrets(
            llmApiKey = updated.llmApiKey.trim(),
            libreApiKey = updated.libreApiKey.trim(),
            myMemoryEmail = updated.myMemoryEmail.trim(),
        )
        // Keys are written first and synchronously: if the process dies right after this line
        // the credential is on disk, and the preference write below is only cosmetics.
        if (nextSecrets != currentSecrets) secrets.save(nextSecrets)

        context.dataStore.edit { prefs ->
            prefs.remove(Keys.llmKey)
            prefs.remove(Keys.libreKey)
            prefs.remove(Keys.myMemoryEmail)
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
            prefs[Keys.libreEndpoint] = updated.libreEndpoint
            prefs[Keys.llmBase] = updated.llmBaseUrl
            prefs[Keys.llmModel] = updated.llmModel
            prefs[Keys.scanOnLaunch] = updated.scanOnLaunch
            prefs[Keys.migrations] = 1
        }
    }

    suspend fun current(): AppSettings = flow.first()

    /** The cached DataStore snapshot — `data` keeps the last read in memory, so this is not I/O. */
    private fun currentPrefs(): Preferences =
        runCatching { kotlinx.coroutines.runBlocking { context.dataStore.data.first() } }
            .getOrDefault(androidx.datastore.preferences.core.emptyPreferences())

    /** Why stored keys are missing, when they could not be decrypted on this device. */
    fun secretsProblem(): String? = secrets.lastFailure

    /**
     * One-time migration: earlier builds kept the key in DataStore as plain text. Move it into
     * the encrypted store and delete the plaintext copy.
     */
    suspend fun migrateLegacyKeys() {
        val prefs = context.dataStore.data.first()
        val legacy = Secrets(
            llmApiKey = prefs[Keys.llmKey]?.trim().orEmpty(),
            libreApiKey = prefs[Keys.libreKey]?.trim().orEmpty(),
            myMemoryEmail = prefs[Keys.myMemoryEmail]?.trim().orEmpty(),
        )
        if (legacy == Secrets.EMPTY) return
        val existing = loadSecrets()
        val merged = Secrets(
            llmApiKey = existing.llmApiKey.ifBlank { legacy.llmApiKey },
            libreApiKey = existing.libreApiKey.ifBlank { legacy.libreApiKey },
            myMemoryEmail = existing.myMemoryEmail.ifBlank { legacy.myMemoryEmail },
        )
        secrets.save(merged)
        context.dataStore.edit { p ->
            p.remove(Keys.llmKey)
            p.remove(Keys.libreKey)
            p.remove(Keys.myMemoryEmail)
        }
    }

    /** Writes a full export/import bundle, so a phone swap does not re-enter anything. */
    fun exportSecrets(): String = Secrets.encode(loadSecrets())

    fun importSecrets(base64: String): Boolean = runCatching {
        val imported = Secrets.decode(base64)
        if (imported == Secrets.EMPTY) return false
        secrets.save(imported)
        true
    }.getOrDefault(false)

    fun clearSecrets() = secrets.clear()
}
