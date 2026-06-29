package com.pocketagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.llm.ModelInfo
import com.pocketagent.storage.ActiveProviderHolder
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val gatewayUrl: String = "",
    val apiKey: String = "",
    val providerName: String = "",
    val providerId: String? = null,
    val activeModelId: String? = null,
    val availableModels: List<ModelInfo> = emptyList(),
    val testing: Boolean = false,
    val testResult: String? = null,
    val versionName: String = com.pocketagent.BuildConfig.VERSION_NAME,
    val systemPrompt: String = "",
    val bashTimeoutSec: Int = 30,
    val workspaceQuotaMb: Int = 2048
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerHolder: ActiveProviderHolder
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.load()
            val provider = settingsRepository.getActiveProvider()
            val s = settingsRepository.settings.value
            if (provider != null) {
                _state.update {
                    it.copy(
                        gatewayUrl = provider.baseUrl,
                        apiKey = provider.apiKey,
                        providerName = provider.displayName,
                        providerId = provider.id,
                        activeModelId = s.activeModelId,
                        systemPrompt = s.systemPrompt,
                        bashTimeoutSec = s.bashCommandTimeoutSec,
                        workspaceQuotaMb = s.workspaceQuotaMb
                    )
                }
                refreshModels()
            } else {
                _state.update {
                    it.copy(
                        systemPrompt = s.systemPrompt,
                        bashTimeoutSec = s.bashCommandTimeoutSec,
                        workspaceQuotaMb = s.workspaceQuotaMb
                    )
                }
            }
        }
    }

    fun onGatewayUrlChange(url: String) = _state.update { it.copy(gatewayUrl = url) }
    fun onApiKeyChange(key: String) = _state.update { it.copy(apiKey = key) }
    fun onProviderNameChange(name: String) = _state.update { it.copy(providerName = name) }
    fun onSystemPromptChange(prompt: String) = _state.update { it.copy(systemPrompt = prompt) }
    fun onBashTimeoutChange(sec: Int) = _state.update { it.copy(bashTimeoutSec = sec) }
    fun onWorkspaceQuotaChange(mb: Int) = _state.update { it.copy(workspaceQuotaMb = mb) }

    /**
     * Save provider config and update active provider ID.
     * Suspends until complete.
     */
    suspend fun save() {
        val s = _state.value
        val id = s.providerId ?: java.util.UUID.randomUUID().toString()
        val config = com.pocketagent.storage.prefs.ProviderConfig(
            id = id,
            displayName = s.providerName.ifBlank { "Gateway" },
            baseUrl = s.gatewayUrl.trimEnd('/'),
            apiKey = s.apiKey.trim()
        )
        settingsRepository.saveProvider(config)
        settingsRepository.updateSettings { it.copy(activeProviderId = id) }
        _state.update { it.copy(providerId = id) }
    }

    /**
     * Save + Test in one call. Properly chained.
     */
    fun saveAndTest() {
        _state.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            try {
                save()
                val provider = settingsRepository.getActiveProvider()
                    ?: throw IllegalStateException("Provider not configured")
                val models = withContext(Dispatchers.IO) { providerHolder.forConfig(provider).listModels() }
                _state.update {
                    it.copy(
                        testing = false,
                        testResult = "Connected. Found ${models.size} models.",
                        availableModels = models
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        testing = false,
                        testResult = "Failed: ${e.message ?: e::class.simpleName}"
                    )
                }
            }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(activeModelId = modelId) }
            _state.update { it.copy(activeModelId = modelId) }
        }
    }

    fun saveSystemPrompt() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(systemPrompt = _state.value.systemPrompt) }
        }
    }

    fun saveBashTimeout() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(bashCommandTimeoutSec = _state.value.bashTimeoutSec) }
        }
    }

    fun saveWorkspaceQuota() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(workspaceQuotaMb = _state.value.workspaceQuotaMb) }
        }
    }

    fun clearTestResult() {
        _state.update { it.copy(testResult = null) }
    }

    private fun refreshModels() {
        viewModelScope.launch {
            val provider = settingsRepository.getActiveProvider() ?: return@launch
            try {
                val models = withContext(Dispatchers.IO) { providerHolder.forConfig(provider).listModels() }
                _state.update { it.copy(availableModels = models) }
            } catch (_: Exception) {}
        }
    }

    fun clearAllKeys() {
        viewModelScope.launch {
            val providers = settingsRepository.providers.value
            providers.forEach { settingsRepository.deleteProvider(it.id) }
            settingsRepository.updateSettings { it.copy(activeProviderId = null, activeModelId = null) }
            _state.update {
                SettingsUiState(
                    versionName = com.pocketagent.BuildConfig.VERSION_NAME,
                    systemPrompt = it.systemPrompt,
                    bashTimeoutSec = it.bashTimeoutSec,
                    workspaceQuotaMb = it.workspaceQuotaMb
                )
            }
        }
    }
}
