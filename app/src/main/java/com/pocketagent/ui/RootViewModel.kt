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
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RootState())
    val state: StateFlow<RootState> = _state.asStateFlow()

    fun bootstrap() {
        viewModelScope.launch {
            settingsRepository.load()
            val s = settingsRepository.settings.value
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
