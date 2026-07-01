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
    val systemPrompt: String = "",          // empty = use default from AgentLoop
    val maxToolIterations: Int = 50,        // H2: max tool calls per turn (5-100); was 30, too low for build tasks
    val tokenSaveMode: Boolean = false,     // H3: if true, truncate results + skip reasoning
    val disabledSkills: String = "",            // v4.3: comma-separated list of disabled skills (saves tokens)
    // v6: Termux bridge settings
    val termuxToken: String = "",               // v6: auth token for ~/.pocketagent/token
    val autoCompactThreshold: Float = 0.7f,     // v6: auto-compact at 70% of context window
    val enableSubagents: Boolean = true,        // v6: allow task() tool to spawn subagents
    val focusMode: Boolean = true,              // v6: eink-style low-contrast theme
    // v7: Cloud (GitHub Codespaces) settings
    val agentMode: String = "TASK",             // v7: TASK (cloud Linux) or CHAT (LLM + web only)
    val githubToken: String = "",               // v7: GitHub PAT for Codespaces API
    val cloudUrl: String = "",                  // v7: codespace daemon URL
    val cloudToken: String = "",                // v7: daemon auth token
    val codespaceName: String = ""              // v7: codespace name (for management)
) {
    enum class ThemeMode { SYSTEM, LIGHT, DARK }
}
