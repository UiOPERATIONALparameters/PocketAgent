package com.pocketagent.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the cloud daemon (in Codespaces).
 */
@Singleton
class CloudState @Inject constructor() {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, ERROR, NO_CODESPACE }
    enum class Mode { CHAT, TASK }  // CHAT = no cloud, TASK = cloud Linux

    data class State(
        val status: Status = Status.DISCONNECTED,
        val mode: Mode = Mode.TASK,
        val daemonVersion: String? = null,
        val cloudUser: String? = null,
        val cloudHome: String? = null,
        val lastError: String? = null,
        val lastConnectedAt: Long? = null,
        val codespaceName: String? = null,
        val codespaceState: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setConnecting() { _state.value = _state.value.copy(status = Status.CONNECTING, lastError = null) }
    fun setConnected(health: HealthResponse) {
        _state.value = _state.value.copy(
            status = Status.CONNECTED, daemonVersion = health.version,
            cloudUser = health.user, cloudHome = health.home,
            lastConnectedAt = System.currentTimeMillis(), lastError = null
        )
    }
    fun setError(msg: String) { _state.value = _state.value.copy(status = Status.ERROR, lastError = msg) }
    fun setDisconnected() { _state.value = _state.value.copy(status = Status.DISCONNECTED) }
    fun setNoCodespace() { _state.value = _state.value.copy(status = Status.NO_CODESPACE) }
    fun setMode(mode: Mode) { _state.value = _state.value.copy(mode = mode) }
    fun setCodespace(name: String?, state: String?) {
        _state.value = _state.value.copy(codespaceName = name, codespaceState = state)
    }

    val isConnected: Boolean get() = _state.value.status == Status.CONNECTED
    val current: State get() = _state.value
}
