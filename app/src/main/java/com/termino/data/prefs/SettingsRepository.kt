package com.termino.data.prefs

import com.termino.core.ai.ReasoningEffort
import com.termino.core.ai.isLocalEndpoint
import com.termino.core.reader.MAX_FONT_SIZE
import com.termino.core.reader.MIN_FONT_SIZE
import com.termino.core.reader.ReaderFont
import com.termino.core.reader.ReaderTheme
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.termino.data.model.AiLanguage
import com.termino.data.model.AiPaneMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "termino_settings"
)

data class AppSettings(
    val baseUrl: String = "",
    val apiKey: String = "",
    val fastModel: String = "",
    val chatModel: String = "",
    /** Provider name as verified (display only). */
    val providerLabel: String = "",
    val aiLanguage: AiLanguage = AiLanguage.AUTO,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.OFF,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 1400,
    val readerTheme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.65f,
    val readerFont: ReaderFont = ReaderFont.SERIF,
    val aiPaneMode: AiPaneMode = AiPaneMode.DOCKED,
    val aiPaneFraction: Float = 0.36f,
    val eyeCare: Boolean = false,
    val onboardingCompleted: Boolean = false,
    /** Tapping the left/right edges turns the page; the center shows the UI. */
    val tapZones: Boolean = true,
    val volumeKeysTurnPages: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** PDF: show the original rendered page (true) or the extracted text. */
    val pdfOriginalPages: Boolean = true,
    val appTheme: com.termino.data.model.AppThemeMode = com.termino.data.model.AppThemeMode.SYSTEM,
    val contextSize: com.termino.data.model.ContextSize = com.termino.data.model.ContextSize.AUTO
) {
    /** Effective context size (AUTO resolved for the server). */
    val effectiveContextSize: com.termino.data.model.ContextSize
        get() = contextSize.resolve(isLocalEndpoint(baseUrl))

    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && chatModel.isNotBlank() &&
            (apiKey.isNotBlank() || isLocalEndpoint(baseUrl))

    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> "Sin configurar"
            apiKey.length <= 8 -> "••••••••"
            else -> apiKey.take(4) + "••••••••" + apiKey.takeLast(4)
        }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val baseUrl = stringPreferencesKey("ai_base_url")
        val apiKey = stringPreferencesKey("ai_api_key")
        val fastModel = stringPreferencesKey("ai_fast_model")
        val chatModel = stringPreferencesKey("ai_chat_model")
        val providerLabel = stringPreferencesKey("ai_provider_label")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val aiLanguage = stringPreferencesKey("ai_language")
        val reasoningEffort = stringPreferencesKey("ai_reasoning_effort")
        val temperature = floatPreferencesKey("ai_temperature")
        val maxTokens = intPreferencesKey("ai_max_tokens")
        val readerTheme = stringPreferencesKey("reader_theme")
        val fontSize = intPreferencesKey("reader_font_size")
        val lineHeight = floatPreferencesKey("reader_line_height")
        val readerFont = stringPreferencesKey("reader_font")
        val aiPaneMode = stringPreferencesKey("ai_pane_mode")
        val aiPaneFraction = floatPreferencesKey("ai_pane_fraction")
        val eyeCare = booleanPreferencesKey("eye_care")
        val tapZones = booleanPreferencesKey("tap_zones")
        val volumeKeys = booleanPreferencesKey("volume_keys_turn_pages")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val pdfOriginalPages = booleanPreferencesKey("pdf_original_pages")
        val appTheme = stringPreferencesKey("app_theme")
        val contextSize = stringPreferencesKey("ai_context_size")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs ->
            AppSettings(
                baseUrl = prefs[Keys.baseUrl].orEmpty(),
                apiKey = prefs[Keys.apiKey].orEmpty(),
                fastModel = prefs[Keys.fastModel].orEmpty(),
                chatModel = prefs[Keys.chatModel].orEmpty(),
                providerLabel = prefs[Keys.providerLabel].orEmpty(),
                aiLanguage = AiLanguage.fromStorage(prefs[Keys.aiLanguage]),
                reasoningEffort = ReasoningEffort.fromStorage(prefs[Keys.reasoningEffort]),
                temperature = prefs[Keys.temperature] ?: 0.3f,
                maxTokens = prefs[Keys.maxTokens] ?: 1400,
                readerTheme = ReaderTheme.fromStorage(prefs[Keys.readerTheme]),
                fontSize = (prefs[Keys.fontSize] ?: 19).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE),
                lineHeight = (prefs[Keys.lineHeight] ?: 1.65f).coerceIn(1.2f, 2.2f),
                readerFont = ReaderFont.fromStorage(prefs[Keys.readerFont]),
                aiPaneMode = AiPaneMode.fromStorage(prefs[Keys.aiPaneMode]),
                aiPaneFraction = (prefs[Keys.aiPaneFraction] ?: 0.36f).coerceIn(0.0f, 0.40f),
                eyeCare = prefs[Keys.eyeCare] ?: false,
                // Installs that already had AI configured skip onboarding.
                onboardingCompleted = prefs[Keys.onboardingCompleted]
                    ?: (prefs[Keys.apiKey]?.isNotBlank() == true),
                tapZones = prefs[Keys.tapZones] ?: true,
                volumeKeysTurnPages = prefs[Keys.volumeKeys] ?: false,
                keepScreenOn = prefs[Keys.keepScreenOn] ?: true,
                pdfOriginalPages = prefs[Keys.pdfOriginalPages] ?: true,
                appTheme = com.termino.data.model.AppThemeMode.fromStorage(prefs[Keys.appTheme]),
                contextSize = com.termino.data.model.ContextSize.fromStorage(prefs[Keys.contextSize])
            )
        }

    suspend fun saveAiSettings(
        baseUrl: String,
        apiKey: String,
        fastModel: String,
        chatModel: String,
        aiLanguage: AiLanguage,
        reasoningEffort: ReasoningEffort,
        temperature: Float,
        maxTokens: Int
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.baseUrl] = baseUrl.trim()
            prefs[Keys.apiKey] = apiKey.trim()
            prefs[Keys.fastModel] = fastModel.trim()
            prefs[Keys.chatModel] = chatModel.trim()
            prefs[Keys.aiLanguage] = aiLanguage.tag
            prefs[Keys.reasoningEffort] = reasoningEffort.name
            prefs[Keys.temperature] = temperature.coerceIn(0f, 1.5f)
            prefs[Keys.maxTokens] = maxTokens.coerceIn(128, 8192)
        }
    }

    /**
     * Saves a connection already verified against the provider (onboarding or
     * "Change connection" in Settings) and marks onboarding as completed.
     */
    suspend fun saveVerifiedConnection(
        baseUrl: String,
        apiKey: String,
        providerLabel: String,
        fastModel: String,
        chatModel: String
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.baseUrl] = baseUrl.trim()
            prefs[Keys.apiKey] = apiKey.trim()
            prefs[Keys.providerLabel] = providerLabel.trim()
            prefs[Keys.fastModel] = fastModel.trim()
            prefs[Keys.chatModel] = chatModel.trim()
            prefs[Keys.onboardingCompleted] = true
        }
    }

    suspend fun saveReasoningEffort(effort: ReasoningEffort) {
        context.settingsDataStore.edit { it[Keys.reasoningEffort] = effort.name }
    }

    suspend fun saveAiLanguage(language: AiLanguage) {
        context.settingsDataStore.edit { it[Keys.aiLanguage] = language.tag }
    }

    suspend fun setOnboardingCompleted(done: Boolean = true) {
        context.settingsDataStore.edit { it[Keys.onboardingCompleted] = done }
    }

    suspend fun saveReaderSettings(
        theme: ReaderTheme,
        fontSize: Int,
        lineHeight: Float,
        font: ReaderFont
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.readerTheme] = theme.name
            prefs[Keys.fontSize] = fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
            prefs[Keys.lineHeight] = lineHeight.coerceIn(1.2f, 2.2f)
            prefs[Keys.readerFont] = font.id
        }
    }

    suspend fun saveTheme(theme: ReaderTheme) {
        context.settingsDataStore.edit { it[Keys.readerTheme] = theme.name }
    }

    suspend fun saveFontSize(size: Int) {
        context.settingsDataStore.edit { it[Keys.fontSize] = size.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE) }
    }

    suspend fun saveLineHeight(value: Float) {
        context.settingsDataStore.edit { it[Keys.lineHeight] = value.coerceIn(1.2f, 2.2f) }
    }

    suspend fun saveReaderFont(font: ReaderFont) {
        context.settingsDataStore.edit { it[Keys.readerFont] = font.id }
    }

    suspend fun savePaneMode(mode: AiPaneMode) {
        context.settingsDataStore.edit { it[Keys.aiPaneMode] = mode.name }
    }

    suspend fun savePaneFraction(fraction: Float) {
        context.settingsDataStore.edit {
            it[Keys.aiPaneFraction] = fraction.coerceIn(0.0f, 0.40f)
        }
    }

    suspend fun saveContextSize(size: com.termino.data.model.ContextSize) {
        context.settingsDataStore.edit { it[Keys.contextSize] = size.name }
    }

    suspend fun saveAppTheme(mode: com.termino.data.model.AppThemeMode) {
        context.settingsDataStore.edit { it[Keys.appTheme] = mode.name }
    }

    suspend fun saveTapZones(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.tapZones] = enabled }
    }

    suspend fun saveVolumeKeys(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.volumeKeys] = enabled }
    }

    suspend fun saveKeepScreenOn(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.keepScreenOn] = enabled }
    }

    suspend fun savePdfOriginalPages(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.pdfOriginalPages] = enabled }
    }

    suspend fun saveEyeCare(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.eyeCare] = enabled }
    }

    /** Forgets the AI connection (key included) without touching reading settings or books. */
    suspend fun clearAiConnection() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(Keys.baseUrl)
            prefs.remove(Keys.apiKey)
            prefs.remove(Keys.providerLabel)
            prefs.remove(Keys.fastModel)
            prefs.remove(Keys.chatModel)
        }
    }
}
