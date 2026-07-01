package com.pocketagent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketagent.storage.prefs.AppSettings
import com.pocketagent.storage.prefs.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RootState(
    val loading: Boolean = true,
    val onboardingComplete: Boolean = false,
    val darkTheme: Boolean = false
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val bridge: com.pocketagent.bridge.TermuxBridge
) : ViewModel() {

    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    fun bootstrap() {
        viewModelScope.launch {
            settingsRepository.load()
            // v6: refresh Termux bridge connection on app start
            bridge.refreshState()
            val s = settingsRepository.settings.value
            val provider = settingsRepository.getActiveProvider()

            // MIGRATION FIX: If onboarding was marked complete (e.g. from v0.2.0 bug
            // where finish() was never called) but no provider is saved, reset
            // onboarding so the user goes through it again.
            if (s.onboardingComplete && provider == null) {
                settingsRepository.updateSettings { it.copy(onboardingComplete = false) }
                _state.update {
                    it.copy(
                        loading = false,
                        onboardingComplete = false,
                        darkTheme = when (s.themeMode) {
                            AppSettings.ThemeMode.LIGHT -> false
                            AppSettings.ThemeMode.DARK -> true
                            AppSettings.ThemeMode.SYSTEM -> it.darkTheme
                        }
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    loading = false,
                    onboardingComplete = s.onboardingComplete,
                    darkTheme = when (s.themeMode) {
                        AppSettings.ThemeMode.LIGHT -> false
                        AppSettings.ThemeMode.DARK -> true
                        AppSettings.ThemeMode.SYSTEM -> it.darkTheme
                    }
                )
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(onboardingComplete = true) }
            _state.update { it.copy(onboardingComplete = true) }
        }
    }
}
