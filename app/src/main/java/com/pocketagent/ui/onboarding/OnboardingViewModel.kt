package com.pocketagent.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.llm.ModelInfo
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.prefs.ProviderConfig
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

data class OnboardingState(
    val step: OnboardingStep = OnboardingStep.PROVIDER,
    val providerName: String = "OpenAI",
    // C9 FIX: was 'https://api.gateway.orgn.com/v1' (previous AI's private test gateway).
    // Now defaults to empty — user must pick a provider from the presets.
    val gatewayUrl: String = "",
    val apiKey: String = "",
    val testing: Boolean = false,
    val saving: Boolean = false,
    val testError: String? = null,
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val savedProvider: ProviderConfig? = null
) {
    enum class OnboardingStep { WELCOME, PROVIDER, MODEL, SAVING, DONE }
}

/** Built-in provider presets for the onboarding dropdown. */
val PROVIDER_PRESETS = listOf(
    ProviderPreset("OpenAI", "https://api.openai.com/v1", "sk-..."),
    ProviderPreset("OpenRouter", "https://openrouter.ai/api/v1", "sk-or-..."),
    ProviderPreset("z.ai (GLM)", "https://open.bigmodel.cn/api/paas/v4", "..."),
    ProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "sk-..."),
    ProviderPreset("Groq", "https://api.groq.com/openai/v1", "gsk_..."),
    ProviderPreset("Together AI", "https://api.together.xyz/v1", "..."),
    ProviderPreset("Mistral", "https://api.mistral.ai/v1", "..."),
    ProviderPreset("Ollama (local)", "http://10.0.2.2:11434/v1", ""),  // emulator: host loopback
    ProviderPreset("LM Studio (local)", "http://10.0.2.2:1234/v1", ""),
    ProviderPreset("Custom", "", "")
)

data class ProviderPreset(val name: String, val baseUrl: String, val keyHint: String)

sealed class OnboardingEvent {
    data object NavigateToChat : OnboardingEvent()
    data class ShowError(val message: String) : OnboardingEvent()
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerHolder: ActiveProviderHolder
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    // Events emitted to the UI for navigation — ensures save completes before nav
    private val _events = MutableSharedFlow<OnboardingEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<OnboardingEvent> = _events.asSharedFlow()

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

    /** M18 FIX: advance from WELCOME step to PROVIDER step. */
    fun advanceFromWelcome() {
        _state.update {
            if (it.step == OnboardingState.OnboardingStep.WELCOME) {
                it.copy(step = OnboardingState.OnboardingStep.PROVIDER)
            } else it
        }
    }

    /** Apply a provider preset (from the dropdown in onboarding). */
    fun applyPreset(preset: com.pocketagent.ui.onboarding.ProviderPreset) {
        _state.update {
            it.copy(
                providerName = preset.name,
                gatewayUrl = preset.baseUrl,
                apiKey = if (preset.baseUrl.isEmpty()) "" else it.apiKey  // don't clear key if switching URLs
            )
        }
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
                    id = s.savedProvider?.id ?: UUID.randomUUID().toString(),
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

    /**
     * CRITICAL: This method saves the provider config, API key, and model ID
     * to persistent storage. It MUST complete before navigation.
     *
     * Emits NavigateToChat event AFTER save completes, so the UI can navigate
     * knowing the data is persisted.
     */
    fun finish() {
        val s = _state.value
        val provider = s.savedProvider ?: run {
            viewModelScope.launch { _events.emit(OnboardingEvent.ShowError("No provider configured")) }
            return
        }
        val modelId = s.selectedModelId ?: run {
            viewModelScope.launch { _events.emit(OnboardingEvent.ShowError("No model selected")) }
            return
        }

        _state.update { it.copy(saving = true, step = OnboardingState.OnboardingStep.SAVING) }
        viewModelScope.launch {
            try {
                // Save provider config (uses commit() — synchronous disk write)
                settingsRepository.saveProvider(provider)
                // Update settings with active provider, model, and onboarding complete
                settingsRepository.updateSettings { settings ->
                    settings.copy(
                        activeProviderId = provider.id,
                        activeModelId = modelId,
                        onboardingComplete = true
                    )
                }
                _state.update { it.copy(saving = false, step = OnboardingState.OnboardingStep.DONE) }
                // Emit navigation event — save is complete, safe to navigate
                _events.emit(OnboardingEvent.NavigateToChat)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        saving = false,
                        step = OnboardingState.OnboardingStep.MODEL,
                        testError = "Failed to save: ${e.message ?: e::class.simpleName}"
                    )
                }
            }
        }
    }
}
