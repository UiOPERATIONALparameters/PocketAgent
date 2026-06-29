package com.pocketagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.llm.ModelInfo
import com.pocketagent.sandbox.BootstrapInstaller
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
    val workspaceQuotaMb: Int = 2048,
    // Bootstrap (Termux) state
    val bootstrapInstalled: Boolean = false,
    val bootstrapInstalling: Boolean = false,
    val bootstrapProgress: Float = 0f,  // 0..1
    val bootstrapStatus: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerHolder: ActiveProviderHolder,
    private val bootstrapInstaller: BootstrapInstaller
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
                        workspaceQuotaMb = s.workspaceQuotaMb,
                        bootstrapInstalled = bootstrapInstaller.isInstalled()
                    )
                }
                refreshModels()
            } else {
                _state.update {
                    it.copy(
                        systemPrompt = s.systemPrompt,
                        bashTimeoutSec = s.bashCommandTimeoutSec,
                        workspaceQuotaMb = s.workspaceQuotaMb,
                        bootstrapInstalled = bootstrapInstaller.isInstalled()
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
            } catch (e: Exception) {
                _state.update {
                    it.copy(testResult = "Couldn't fetch models: ${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    /**
     * Download and install the Termux bootstrap (~50MB).
     * Gives the AI full Linux: apt install, python, node, git, ffmpeg, etc.
     */
    fun installBootstrap() {
        if (_state.value.bootstrapInstalling) return
        _state.update {
            it.copy(
                bootstrapInstalling = true,
                bootstrapProgress = 0f,
                bootstrapStatus = "Downloading Linux environment (50MB)…"
            )
        }
        viewModelScope.launch {
            val result = bootstrapInstaller.install { downloaded, total ->
                if (total > 0) {
                    val pct = downloaded.toFloat() / total
                    _state.update {
                        it.copy(
                            bootstrapProgress = pct,
                            bootstrapStatus = "Downloading… ${(pct * 100).toInt()}%"
                        )
                    }
                } else if (downloaded > 0) {
                    _state.update {
                        it.copy(
                            bootstrapStatus = "Extracting… ${(downloaded / 1024 / 1024)}MB"
                        )
                    }
                }
            }
            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        bootstrapInstalling = false,
                        bootstrapInstalled = true,
                        bootstrapProgress = 1f,
                        bootstrapStatus = "Linux environment installed! The AI now has full Linux access."
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        bootstrapInstalling = false,
                        bootstrapStatus = "Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun uninstallBootstrap() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { bootstrapInstaller.uninstall() }
            _state.update {
                it.copy(
                    bootstrapInstalled = false,
                    bootstrapStatus = "Linux environment removed."
                )
            }
        }
    }

    /**
     * Verify the bootstrap installation and repair if needed.
     */
    fun verifyAndRepairBootstrap() {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                // First try to repair permissions
                bootstrapInstaller.repairPermissions()
                // Then verify
                bootstrapInstaller.verify()
            }
            if (error == null) {
                _state.update {
                    it.copy(
                        bootstrapInstalled = true,
                        bootstrapStatus = "Linux environment verified OK."
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        bootstrapStatus = "Verification failed: $error\nTry removing and reinstalling."
                    )
                }
            }
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
                    workspaceQuotaMb = it.workspaceQuotaMb,
                    bootstrapInstalled = it.bootstrapInstalled
                )
            }
        }
    }
}
