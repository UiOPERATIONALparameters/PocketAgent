package com.pocketagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.ModelInfo
import com.pocketagent.llm.OpenAICompatibleProvider
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.prefs.ProviderConfig
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.PROVIDER,
    val providerName: String = "My Gateway",
    val gatewayUrl: String = "https://api.gateway.orgn.com/v1",
    val apiKey: String = "",
    val testing: Boolean = false,
    val testError: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val savedProvider: ProviderConfig? = null
) {
    enum class OnboardingStep { WELCOME, PROVIDER, MODEL, DONE }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerHolder: ActiveProviderHolder
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // load() is idempotent — safe to call from any ViewModel
            settingsRepository.load()
            // If there's an existing provider, pre-fill the fields
            val existing = settingsRepository.getActiveProvider()
            if (existing != null) {
                _state.update {
                    it.copy(
                        providerName = existing.displayName,
                        gatewayUrl = existing.baseUrl,
                        apiKey = existing.apiKey,
                        savedProvider = existing
                    )
                }
            }
        }
    }

    fun onProviderNameChange(name: String) {
        _state.update { it.copy(providerName = name) }
    }

    fun onGatewayUrlChange(url: String) {
        _state.update { it.copy(gatewayUrl = url) }
    }

    fun onApiKeyChange(key: String) {
        _state.update { it.copy(apiKey = key) }
    }

    fun testConnection() {
        val s = _state.value
        if (s.gatewayUrl.isBlank() || s.apiKey.isBlank()) {
            _state.update { it.copy(testError = "Gateway URL and API key required") }
            return
        }
        _state.update { it.copy(testing = true, testError = null) }
        viewModelScope.launch {
            try {
                val config = ProviderConfig(
                    id = UUID.randomUUID().toString(),
                    displayName = s.providerName.ifBlank { "My Gateway" },
                    baseUrl = s.gatewayUrl.trimEnd('/'),
                    apiKey = s.apiKey.trim()
                )
                val provider = providerHolder.forConfig(config)
                val models = withContext(Dispatchers.IO) { provider.listModels() }
                if (models.isEmpty()) {
                    _state.update {
                        it.copy(
                            testing = false,
                            testError = "Connected but no models returned"
                        )
                    }
                    return@launch
                }
                _state.update {
                    it.copy(
                        testing = false,
                        testError = null,
                        models = models,
                        savedProvider = config,
                        step = OnboardingState.OnboardingStep.MODEL
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        testing = false,
                        testError = e.message ?: e::class.simpleName
                    )
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        _state.update { it.copy(selectedModelId = modelId) }
    }

    fun finish() {
        val s = _state.value
        val provider = s.savedProvider ?: return
        val modelId = s.selectedModelId ?: return
        viewModelScope.launch {
            settingsRepository.saveProvider(provider)
            settingsRepository.updateSettings { settings ->
                settings.copy(
                    activeProviderId = provider.id,
                    activeModelId = modelId,
                    onboardingComplete = true
                )
            }
            _state.update { it.copy(step = OnboardingState.OnboardingStep.DONE) }
        }
    }
}
