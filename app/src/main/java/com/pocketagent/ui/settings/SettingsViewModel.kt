package com.pocketagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.cloud.CloudState
import com.pocketagent.cloud.CloudBridge
import com.pocketagent.cloud.CodespacesManager
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
    val workspaceQuotaMb: Int = 2048,
    val maxToolIterations: Int = 50,
    val tokenSaveMode: Boolean = false,
    val disabledSkills: String = "",
    // v7: Cloud settings
    val agentMode: String = "TASK",
    val githubToken: String = "",
    val cloudUrl: String = "",
    val cloudToken: String = "",
    val cloudConnected: Boolean = false,
    val cloudVersion: String? = null,
    val cloudUser: String? = null,
    val cloudStatus: String = "",
    // v6 retained
    val autoCompactThreshold: Float = 0.7f,
    val enableSubagents: Boolean = true,
    val focusMode: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerHolder: ActiveProviderHolder,
    private val cloud: CloudBridge,
    private val codespacesManager: CodespacesManager
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
                        maxToolIterations = s.maxToolIterations,
                        tokenSaveMode = s.tokenSaveMode,
                        agentMode = s.agentMode,
                        githubToken = s.githubToken,
                        cloudUrl = s.cloudUrl,
                        cloudToken = s.cloudToken,
                        autoCompactThreshold = s.autoCompactThreshold,
                        enableSubagents = s.enableSubagents,
                        focusMode = s.focusMode
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
                        agentMode = s.agentMode,
                        githubToken = s.githubToken,
                        cloudUrl = s.cloudUrl,
                        cloudToken = s.cloudToken,
                        autoCompactThreshold = s.autoCompactThreshold,
                        enableSubagents = s.enableSubagents,
                        focusMode = s.focusMode
                    )
                }
            }
            launch {
                cloud.state.state.collect { cs ->
                    _state.update {
                        it.copy(
                            cloudConnected = cs.status == CloudState.Status.CONNECTED,
                            cloudVersion = cs.daemonVersion,
                            cloudUser = cs.cloudUser,
                            cloudStatus = cs.status.name
                        )
                    }
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
    fun onDisabledSkillsChange(skills: String) = _state.update { it.copy(disabledSkills = skills) }
    fun onAgentModeChange(mode: String) {
        _state.update { it.copy(agentMode = mode) }
        viewModelScope.launch { settingsRepository.updateSettings { it.copy(agentMode = mode) } }
    }
    fun onGithubTokenChange(token: String) = _state.update { it.copy(githubToken = token) }
    fun onCloudUrlChange(url: String) = _state.update { it.copy(cloudUrl = url) }
    fun onCloudTokenChange(token: String) = _state.update { it.copy(cloudToken = token) }
    fun onAutoCompactThresholdChange(v: Float) = _state.update { it.copy(autoCompactThreshold = v) }
    fun onEnableSubagentsChange(v: Boolean) = _state.update { it.copy(enableSubagents = v) }
    fun onFocusModeChange(v: Boolean) = _state.update { it.copy(focusMode = v) }

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
                _state.update { it.copy(testing = false, testResult = "Connected. Found ${models.size} models.", availableModels = models) }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, testResult = "Failed: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun selectModel(modelId: String) {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(activeModelId = modelId) }
            _state.update { it.copy(activeModelId = modelId) }
        }
    }

    fun saveSystemPrompt() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(systemPrompt = _state.value.systemPrompt) } } }
    fun saveBashTimeout() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(bashCommandTimeoutSec = _state.value.bashTimeoutSec) } } }
    fun saveMaxIterations() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(maxToolIterations = _state.value.maxToolIterations) } } }
    fun saveTokenSaveMode() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(tokenSaveMode = _state.value.tokenSaveMode) } } }

    fun saveCloudSettings() {
        viewModelScope.launch {
            settingsRepository.updateSettings {
                it.copy(githubToken = _state.value.githubToken.trim(), cloudUrl = _state.value.cloudUrl.trim(), cloudToken = _state.value.cloudToken.trim())
            }
            cloud.refreshState()
        }
    }

    fun refreshCloudConnection() { viewModelScope.launch { cloud.refreshState() } }

    fun createCodespace() {
        viewModelScope.launch {
            _state.update { it.copy(cloudStatus = "Creating codespace...") }
            val result = codespacesManager.ensureCodespace()
            if (result.isSuccess) {
                val (name, url) = result.getOrNull()!!
                settingsRepository.updateSettings { it.copy(codespaceName = name, cloudUrl = url) }
                _state.update { it.copy(cloudUrl = url, cloudStatus = "Codespace ready: $name") }
                cloud.refreshState()
            } else {
                _state.update { it.copy(cloudStatus = "Failed: ${result.exceptionOrNull()?.message}") }
            }
        }
    }

    fun saveAutoCompactThreshold() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(autoCompactThreshold = _state.value.autoCompactThreshold) } } }
    fun saveEnableSubagents() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(enableSubagents = _state.value.enableSubagents) } } }
    fun saveFocusMode() { viewModelScope.launch { settingsRepository.updateSettings { it.copy(focusMode = _state.value.focusMode) } } }
    fun clearTestResult() { _state.update { it.copy(testResult = null) } }

    private fun refreshModels() {
        viewModelScope.launch {
            val provider = settingsRepository.getActiveProvider() ?: return@launch
            try {
                val models = withContext(Dispatchers.IO) { providerHolder.forConfig(provider).listModels() }
                _state.update { it.copy(availableModels = models) }
            } catch (e: Exception) {
                _state.update { it.copy(testResult = "Couldn't fetch models: ${e.message ?: e::class.simpleName}") }
            }
        }
    }

    fun clearAllKeys() {
        viewModelScope.launch {
            val providers = settingsRepository.providers.value
            providers.forEach { settingsRepository.deleteProvider(it.id) }
            settingsRepository.updateSettings { it.copy(activeProviderId = null, activeModelId = null) }
            _state.update {
                SettingsUiState(versionName = com.pocketagent.BuildConfig.VERSION_NAME, systemPrompt = it.systemPrompt, bashTimeoutSec = it.bashTimeoutSec, workspaceQuotaMb = it.workspaceQuotaMb)
            }
        }
    }
}
