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
    val darkTheme: Boolean = false,
    val focusMode: Boolean = true  // v6: eink aesthetic, default on
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val cloud: com.pocketagent.cloud.CloudBridge
) : ViewModel() {

    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    fun bootstrap() {
        viewModelScope.launch {
            settingsRepository.load()
            // v6: refresh Termux bridge connection on app start
            cloud.refreshState()
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
                        },
                        focusMode = s.focusMode
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
                    },
                    focusMode = s.focusMode
                )
            }

            // v6: Observe settings changes so focus mode toggle in Settings
            // immediately re-renders the theme (without requiring app restart).
            settingsRepository.settings.collect { settings ->
                _state.update {
                    it.copy(
                        focusMode = settings.focusMode,
                        darkTheme = when (settings.themeMode) {
                            AppSettings.ThemeMode.LIGHT -> false
                            AppSettings.ThemeMode.DARK -> true
                            AppSettings.ThemeMode.SYSTEM -> it.darkTheme
                        }
                    )
                }
            }
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(onboardingComplete = true) }
            _state.update { it.copy(onboardingComplete = true) }
        }
    }

    /** v6: Update focus mode at the root level so the theme re-renders immediately. */
    fun updateFocusMode(enabled: Boolean) {
        _state.update { it.copy(focusMode = enabled) }
    }
}
