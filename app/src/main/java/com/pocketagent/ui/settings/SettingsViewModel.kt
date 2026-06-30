package com.pocketagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.llm.ModelInfo
import com.pocketagent.sandbox.LinuxEnvironmentManager
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
    val maxToolIterations: Int = 50,
    val tokenSaveMode: Boolean = false,
    // Linux environment state (v2.1 — replaces bootstrap)
    val linuxInstalled: Boolean = false,
    val linuxInstalling: Boolean = false,
    val linuxProgress: Float = 0f,
    val linuxStatus: String = "",
    val linuxAbi: String = "",
    val linuxDistro: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerHolder: ActiveProviderHolder,
    private val linuxEnv: LinuxEnvironmentManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.load()
            val provider = settingsRepository.getActiveProvider()
            val s = settingsRepository.settings.value
            val abi = LinuxEnvironmentManager.detectAbi()
            val distro = if (LinuxEnvironmentManager.isUbuntu(abi)) "Ubuntu 22.04 LTS" else "Alpine 3.20"
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
                        maxToolIterations = s.maxToolIterations,
                        tokenSaveMode = s.tokenSaveMode,
                        linuxInstalled = linuxEnv.isInstalled(),
                        linuxAbi = abi,
                        linuxDistro = distro
                    )
                }
                refreshModels()
            } else {
                _state.update {
                    it.copy(
                        systemPrompt = s.systemPrompt,
                        bashTimeoutSec = s.bashCommandTimeoutSec,
                        workspaceQuotaMb = s.workspaceQuotaMb,
                        maxToolIterations = s.maxToolIterations,
                        tokenSaveMode = s.tokenSaveMode,
                        linuxInstalled = linuxEnv.isInstalled(),
                        linuxAbi = abi,
                        linuxDistro = distro
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
    fun onMaxIterationsChange(iterations: Int) = _state.update { it.copy(maxToolIterations = iterations) }
    fun onTokenSaveModeChange(enabled: Boolean) = _state.update { it.copy(tokenSaveMode = enabled) }

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

    fun saveMaxIterations() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(maxToolIterations = _state.value.maxToolIterations) }
        }
    }

    fun saveTokenSaveMode() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(tokenSaveMode = _state.value.tokenSaveMode) }
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
     * v2.1: Install the Linux environment (Ubuntu 22.04 via proot).
     *
     * This replaces the old Termux bootstrap approach. The new approach:
     *   1. Extracts a static proot binary from APK assets (~1MB, instant)
     *   2. Downloads Ubuntu 22.04 base rootfs (~28MB)
     *   3. Extracts it (~150MB on disk)
     *   4. Configures DNS, /tmp, /root
     *
     * Once installed, the agent has: bash, apt, python3, perl, AND can install
     * node, git, gcc, ffmpeg, ImageMagick, anything via `apt install`.
     */
    fun installLinux() {
        if (_state.value.linuxInstalling) return

        // Check storage first (need ~300MB free)
        if (!linuxEnv.hasEnoughStorage(300)) {
            _state.update {
                it.copy(linuxStatus = "Not enough free storage. Need at least 300MB free.")
            }
            return
        }

        _state.update {
            it.copy(
                linuxInstalling = true,
                linuxProgress = 0f,
                linuxStatus = "Starting installation…"
            )
        }
        viewModelScope.launch {
            val result = linuxEnv.installRootfs { status, downloaded, total ->
                if (total > 0) {
                    val pct = downloaded.toFloat() / total
                    _state.update {
                        it.copy(
                            linuxProgress = pct,
                            linuxStatus = "$status ${(pct * 100).toInt()}%"
                        )
                    }
                } else if (downloaded > 0) {
                    _state.update {
                        it.copy(
                            linuxProgress = -1f,  // indeterminate
                            linuxStatus = "$status ${(downloaded / 1024 / 1024)}MB"
                        )
                    }
                } else {
                    _state.update {
                        it.copy(linuxStatus = status)
                    }
                }
            }
            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        linuxInstalling = false,
                        linuxInstalled = true,
                        linuxProgress = 1f,
                        linuxStatus = "Linux installed! The AI now has full Ubuntu access (apt, python3, + can install anything)."
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        linuxInstalling = false,
                        linuxStatus = "Failed: ${result.exceptionOrNull()?.message ?: "unknown error"}"
                    )
                }
            }
        }
    }

    fun uninstallLinux() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { linuxEnv.uninstall() }
            _state.update {
                it.copy(
                    linuxInstalled = false,
                    linuxStatus = "Linux environment removed."
                )
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
                    linuxInstalled = it.linuxInstalled,
                    linuxAbi = it.linuxAbi,
                    linuxDistro = it.linuxDistro
                )
            }
        }
    }
}
