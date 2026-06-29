package com.pocketagent.storage.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted persistence for provider configs and app settings.
 *
 * Uses EncryptedSharedPreferences (AES-256-GCM, hardware-backed where available).
 * API keys never touch disk in plaintext.
 *
 * CRITICAL: All writes use commit() (synchronous) instead of apply() (async)
 * to prevent race conditions where a subsequent load() reads stale data.
 *
 * CRITICAL: load() is idempotent — it only runs once at app startup via
 * PocketAgentApp.onCreate(). Subsequent calls are no-ops. This prevents
 * a ViewModel's init block from re-reading disk and overwriting in-memory
 * state that was just written by another ViewModel.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val loadMutex = Mutex()
    private var loaded = false

    /**
     * Load from disk. Idempotent — only the first call actually reads.
     * Safe to call from any ViewModel's init block.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            if (loaded) return@withLock
            loaded = true

            val providerJson = prefs.getString(KEY_PROVIDERS, null)
            val providers = if (providerJson != null) {
                try {
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
                        providerJson
                    )
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            _providers.value = providers

            val settingsJson = prefs.getString(KEY_SETTINGS, null)
            val settings = if (settingsJson != null) {
                try {
                    json.decodeFromString(AppSettings.serializer(), settingsJson)
                } catch (e: Exception) {
                    AppSettings()
                }
            } else AppSettings()
            _settings.value = settings
        }
    }

    /**
     * Force reload from disk. Use sparingly — only when you know external changes happened.
     */
    suspend fun forceReload() = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            loaded = false
        }
        load()
    }

    suspend fun saveProvider(config: ProviderConfig) = withContext(Dispatchers.IO) {
        val updated = _providers.value.toMutableList().apply {
            val idx = indexOfFirst { it.id == config.id }
            if (idx >= 0) this[idx] = config else add(config)
        }
        _providers.value = updated
        // Use commit() (sync) instead of apply() (async) to prevent race with load()
        prefs.edit().putString(
            KEY_PROVIDERS,
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
                updated
            )
        ).commit()
    }

    suspend fun deleteProvider(id: String) = withContext(Dispatchers.IO) {
        val updated = _providers.value.filter { it.id != id }
        _providers.value = updated
        prefs.edit().putString(
            KEY_PROVIDERS,
            json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ProviderConfig.serializer()),
                updated
            )
        ).commit()
        if (_settings.value.activeProviderId == id) {
            updateSettingsInternal(_settings.value.copy(activeProviderId = null, activeModelId = null))
        }
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = withContext(Dispatchers.IO) {
        val updated = transform(_settings.value)
        updateSettingsInternal(updated)
    }

    private fun updateSettingsInternal(updated: AppSettings) {
        _settings.value = updated
        // commit() for consistency
        prefs.edit().putString(
            KEY_SETTINGS,
            json.encodeToString(AppSettings.serializer(), updated)
        ).commit()
    }

    fun getActiveProvider(): ProviderConfig? {
        val id = _settings.value.activeProviderId ?: return null
        return _providers.value.firstOrNull { it.id == id }
    }

    companion object {
        private const val FILE_NAME = "pocketagent_secure_prefs"
        private const val KEY_PROVIDERS = "providers_v1"
        private const val KEY_SETTINGS = "settings_v1"
    }
}
