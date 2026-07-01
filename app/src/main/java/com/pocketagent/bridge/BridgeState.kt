package com.pocketagent.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection state for the Termux daemon.
 *
 * State machine:
 *   DISCONNECTED → CONNECTING → CONNECTED
 *        ↑              ↓            ↓
 *        └──────────────┴────────────┘ (on error)
 *
 * The UI observes [state] and shows appropriate UI:
 *   - DISCONNECTED: "Open Termux and run `pocketagent-daemon`" + Reconnect button
 *   - CONNECTING: spinner
 *   - CONNECTED: green dot, hidden banner
 *   - ERROR: red banner with last error
 */
@Singleton
class BridgeState @Inject constructor() {
    enum class Status {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    data class State(
        val status: Status = Status.DISCONNECTED,
        val daemonVersion: String? = null,
        val termuxUser: String? = null,
        val termuxHome: String? = null,
        val lastError: String? = null,
        val lastConnectedAt: Long? = null,
        val uptimeSeconds: Long = 0
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun setConnecting() {
        _state.value = _state.value.copy(status = Status.CONNECTING, lastError = null)
    }

    fun setConnected(health: HealthResponse) {
        _state.value = State(
            status = Status.CONNECTED,
            daemonVersion = health.version,
            termuxUser = health.user,
            termuxHome = health.home,
            lastConnectedAt = System.currentTimeMillis(),
            uptimeSeconds = health.uptime,
            lastError = null
        )
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(
            status = Status.ERROR,
            lastError = message
        )
    }

    fun setDisconnected() {
        _state.value = _state.value.copy(status = Status.DISCONNECTED)
    }

    val isConnected: Boolean get() = _state.value.status == Status.CONNECTED

    /** Convenience accessor for the current state snapshot. */
    val current: State get() = _state.value
}
