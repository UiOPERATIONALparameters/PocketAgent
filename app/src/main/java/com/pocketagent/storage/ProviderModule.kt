package com.pocketagent.storage

import android.content.Context
import com.pocketagent.llm.LlmProvider
import com.pocketagent.llm.OpenAICompatibleProvider
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}

@Module
@InstallIn(SingletonComponent::class)
object ProviderModule {

    /**
     * Returns the currently active LLM provider, or null if not configured.
     * Caller should check for null and prompt user to configure provider.
     */
    @Provides
    @Singleton
    fun provideActiveProviderFactory(
        settingsRepository: SettingsRepository,
        httpClient: OkHttpClient
    ): ActiveProviderHolder = ActiveProviderHolder(settingsRepository, httpClient)
}

class ActiveProviderHolder(
    private val settingsRepository: SettingsRepository,
    private val httpClient: OkHttpClient
) {
    fun get(): LlmProvider? {
        val config = settingsRepository.getActiveProvider() ?: return null
        return OpenAICompatibleProvider(config, httpClient)
    }

    fun forConfig(config: com.pocketagent.storage.prefs.ProviderConfig): LlmProvider {
        return OpenAICompatibleProvider(config, httpClient)
    }
}
