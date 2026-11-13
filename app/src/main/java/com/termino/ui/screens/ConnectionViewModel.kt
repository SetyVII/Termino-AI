package com.termino.ui.screens

import com.termino.core.ai.ProviderEndpoint
import com.termino.core.ai.ProviderVerifier
import com.termino.core.ai.VerifiedConnection
import com.termino.core.ai.VerifyFailure
import com.termino.core.ai.VerifyResult
import com.termino.core.ai.VerifyStage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.termino.TerminoApp
import com.termino.data.prefs.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionUiState(
    /** Lo único obligatorio: una API Key o la URL de un servidor compatible. */
    val input: String = "",
    val providerId: String? = null,
    /** true si el proveedor lo eligió la app por el prefijo de la clave. */
    val providerSuggested: Boolean = false,
    val apiKeyForUrl: String = "",
    val showAdvanced: Boolean = false,
    val manualFastModel: String = "",
    val manualChatModel: String = "",
    val stage: VerifyStage? = null,
    val failure: VerifyFailure? = null,
    val failureDetail: String? = null,
    val result: VerifiedConnection? = null,
    val saved: Boolean = false
) {
    val isUrl: Boolean get() = ProviderEndpoint.looksLikeUrl(input)
    val provider: ProviderEndpoint? get() = ProviderEndpoint.known.firstOrNull { it.id == providerId }
    val running: Boolean get() = stage != null && stage != VerifyStage.DONE && failure == null
    val canVerify: Boolean
        get() = !running && input.isNotBlank() && (isUrl || provider != null)
}

class ConnectionViewModel(
    private val verifier: ProviderVerifier,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectionUiState())
    val state: StateFlow<ConnectionUiState> = _state.asStateFlow()

    private var verifyJob: Job? = null

    fun setInput(value: String) {
        _state.update { current ->
            val suggestion = ProviderEndpoint.suggestForKey(value)
            val keepManual = current.providerId != null && !current.providerSuggested
            current.copy(
                input = value,
                providerId = when {
                    keepManual -> current.providerId
                    suggestion != null -> suggestion.id
                    current.providerSuggested -> null
                    else -> current.providerId
                },
                providerSuggested = if (keepManual) false else suggestion != null,
                failure = null,
                failureDetail = null,
                result = null,
                stage = null
            )
        }
    }

    fun selectProvider(id: String) {
        _state.update {
            it.copy(
                providerId = if (it.providerId == id && !it.providerSuggested) null else id,
                providerSuggested = false,
                failure = null,
                result = null,
                stage = null
            )
        }
    }

    fun setApiKeyForUrl(value: String) = _state.update { it.copy(apiKeyForUrl = value, failure = null) }
    fun toggleAdvanced() = _state.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun setManualFast(value: String) = _state.update { it.copy(manualFastModel = value) }
    fun setManualChat(value: String) = _state.update { it.copy(manualChatModel = value) }

    fun verify(saveOnSuccess: Boolean = true) {
        val snapshot = _state.value
        if (!snapshot.canVerify) {
            if (!snapshot.isUrl && snapshot.provider == null && snapshot.input.isNotBlank()) {
                _state.update { it.copy(failure = VerifyFailure.MISSING_PROVIDER) }
            }
            return
        }
        verifyJob?.cancel()
        _state.update {
            it.copy(stage = VerifyStage.CONNECTING, failure = null, failureDetail = null, result = null, saved = false)
        }
        verifyJob = viewModelScope.launch {
            val result = verifier.verify(
                input = snapshot.input,
                endpoint = snapshot.provider,
                apiKeyForUrl = snapshot.apiKeyForUrl,
                manualFastModel = snapshot.manualFastModel,
                manualChatModel = snapshot.manualChatModel,
                onStage = { stage -> _state.update { it.copy(stage = stage) } }
            )
            when (result) {
                is VerifyResult.Success -> {
                    _state.update { it.copy(stage = VerifyStage.DONE, result = result.connection) }
                    if (saveOnSuccess) save(result.connection)
                }
                is VerifyResult.Failure -> _state.update {
                    it.copy(stage = null, failure = result.reason, failureDetail = result.detail?.take(220))
                }
            }
        }
    }

    /** Cambia los modelos elegidos tras verificar (ambos vienen del proveedor). */
    fun chooseModels(fast: String, chat: String) {
        val current = _state.value.result ?: return
        val updated = current.copy(fastModel = fast, chatModel = chat)
        _state.update { it.copy(result = updated) }
        save(updated)
    }

    private fun save(connection: VerifiedConnection) {
        viewModelScope.launch {
            settingsRepository.saveVerifiedConnection(
                baseUrl = connection.baseUrl,
                apiKey = connection.apiKey,
                providerLabel = connection.providerLabel,
                fastModel = connection.fastModel,
                chatModel = connection.chatModel
            )
            _state.update { it.copy(saved = true) }
        }
    }

    fun skipOnboarding() {
        viewModelScope.launch { settingsRepository.setOnboardingCompleted(true) }
    }

    fun reset() {
        verifyJob?.cancel()
        _state.value = ConnectionUiState()
    }

    companion object {
        fun factory(app: TerminoApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ConnectionViewModel(
                    verifier = app.serviceLocator.providerVerifier,
                    settingsRepository = app.serviceLocator.settingsRepository
                )
            }
        }
    }
}
