package com.pocketagent.storage.prefs

import kotlinx.serialization.Serializable

/**
 * Persisted configuration for a single BYOK provider.
 */
@Serializable
data class ProviderConfig(
    val id: String,                    // stable unique id
    val displayName: String,           // human name
    val baseUrl: String,               // e.g. https://api.gateway.orgn.com/v1
    val apiKey: String,                // stored encrypted at rest
    val type: ProviderType = ProviderType.OPENAI_COMPATIBLE
) {
    enum class ProviderType {
        OPENAI_COMPATIBLE,
        ANTHROPIC,
        GEMINI
    }
}

/**
 * App-level settings persisted to DataStore.
 */
@Serializable
data class AppSettings(
    val activeProviderId: String? = null,
    val activeModelId: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentColorHex: String? = null,    // null = default teal
    val onboardingComplete: Boolean = false,
    val workspaceQuotaMb: Int = 2048,      // max MB for agent's ~/ dir
    val bashCommandTimeoutSec: Int = 30,
    val systemPrompt: String = ""          // empty = use default from AgentLoop
) {
    enum class ThemeMode { SYSTEM, LIGHT, DARK }
}
