package com.termino.ui.screens

import com.termino.core.ai.ProviderModel
import com.termino.core.ai.ReasoningEffort
import com.termino.core.reader.ReaderFont
import com.termino.core.reader.ReaderTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.termino.TerminoApp
import com.termino.ai.AiRepository
import com.termino.data.model.AiLanguage
import com.termino.data.prefs.AppSettings
import com.termino.data.prefs.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionTestState(
    val running: Boolean = false,
    val success: Boolean? = null,
    val message: String? = null
)

data class ProviderModelsState(
    val loading: Boolean = false,
    val models: List<ProviderModel> = emptyList(),
    val loaded: Boolean = false
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val aiRepository: AiRepository,
    private val fontRepository: com.termino.data.fonts.FontRepository
) : ViewModel() {

    val fonts: StateFlow<List<ReaderFont>> = fontRepository.fonts

    fun typeface(font: ReaderFont): android.graphics.Typeface = fontRepository.typeface(font)

    fun importFont(uri: android.net.Uri) {
        viewModelScope.launch {
            fontRepository.import(uri).onSuccess { settingsRepository.saveReaderFont(it) }
        }
    }

    fun deleteFont(font: ReaderFont) {
        fontRepository.delete(font)
        if (settings.value.readerFont == font) {
            viewModelScope.launch { settingsRepository.saveReaderFont(ReaderFont.SERIF) }
        }
    }

    fun setContextSize(size: com.termino.data.model.ContextSize) =
        viewModelScope.launch { settingsRepository.saveContextSize(size) }

    fun setAppTheme(mode: com.termino.data.model.AppThemeMode) =
        viewModelScope.launch { settingsRepository.saveAppTheme(mode) }

    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AppSettings()
    )

    private val _testState = MutableStateFlow(ConnectionTestState())
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val _models = MutableStateFlow(ProviderModelsState())
    val models: StateFlow<ProviderModelsState> = _models.asStateFlow()

    private var modelsJob: Job? = null

    init {
        viewModelScope.launch {
            val saved = settingsRepository.settings.firstOrNull() ?: return@launch
            if (saved.isConfigured) refreshModels(saved.baseUrl, saved.apiKey)
        }
    }

    /** GET {base}/models with provider metadata (not cached). */
    fun refreshModels(baseUrl: String, apiKey: String) {
        val trimmedBase = baseUrl.trim()
        modelsJob?.cancel()
        if (trimmedBase.isBlank()) {
            _models.value = ProviderModelsState()
            return
        }
        _models.value = _models.value.copy(loading = true)
        modelsJob = viewModelScope.launch {
            val response = aiRepository.fetchModels(
                AppSettings(baseUrl = trimmedBase, apiKey = apiKey.trim())
            )
            _models.value = ProviderModelsState(loading = false, models = response.models, loaded = true)
        }
    }

    fun saveAdvanced(draft: AppSettings) {
        viewModelScope.launch {
            settingsRepository.saveAiSettings(
                baseUrl = draft.baseUrl,
                apiKey = draft.apiKey,
                fastModel = draft.fastModel,
                chatModel = draft.chatModel,
                aiLanguage = draft.aiLanguage,
                reasoningEffort = draft.reasoningEffort,
                temperature = draft.temperature,
                maxTokens = draft.maxTokens
            )
        }
    }

    fun setAiLanguage(language: AiLanguage) =
        viewModelScope.launch { settingsRepository.saveAiLanguage(language) }

    fun setReasoning(effort: ReasoningEffort) =
        viewModelScope.launch { settingsRepository.saveReasoningEffort(effort) }

    fun setTheme(theme: ReaderTheme) = viewModelScope.launch { settingsRepository.saveTheme(theme) }
    fun setFontSize(size: Int) = viewModelScope.launch { settingsRepository.saveFontSize(size) }
    fun setLineHeight(value: Float) = viewModelScope.launch { settingsRepository.saveLineHeight(value) }
    fun setFont(font: ReaderFont) = viewModelScope.launch { settingsRepository.saveReaderFont(font) }
    fun setEyeCare(enabled: Boolean) = viewModelScope.launch { settingsRepository.saveEyeCare(enabled) }
    fun setTapZones(enabled: Boolean) = viewModelScope.launch { settingsRepository.saveTapZones(enabled) }
    fun setVolumeKeys(enabled: Boolean) = viewModelScope.launch { settingsRepository.saveVolumeKeys(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch { settingsRepository.saveKeepScreenOn(enabled) }
    fun setPdfPages(enabled: Boolean) = viewModelScope.launch { settingsRepository.savePdfOriginalPages(enabled) }

    fun testConnection(draft: AppSettings) {
        if (_testState.value.running) return
        _testState.value = ConnectionTestState(running = true)
        viewModelScope.launch {
            val result = aiRepository.testConnection(draft)
            _testState.value = result.fold(
                onSuccess = { ConnectionTestState(success = true, message = it) },
                onFailure = { ConnectionTestState(success = false, message = it.message ?: "Error") }
            )
        }
    }

    fun forgetConnection() {
        viewModelScope.launch {
            settingsRepository.clearAiConnection()
            _models.value = ProviderModelsState()
            _testState.value = ConnectionTestState()
        }
    }

    fun showOnboardingAgain() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(false) }
    }

    fun clearTestState() {
        _testState.value = ConnectionTestState()
    }

    companion object {
        fun factory(app: TerminoApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    settingsRepository = app.serviceLocator.settingsRepository,
                    aiRepository = app.serviceLocator.aiRepository,
                    fontRepository = app.serviceLocator.fontRepository
                )
            }
        }
    }
}
