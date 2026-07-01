package com.pocketagent.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketagent.design.PocketTheme
import com.pocketagent.design.extendedColors
import com.pocketagent.ui.chat.ChatScreen
import com.pocketagent.ui.chat.ChatViewModel
import com.pocketagent.ui.files.FileBrowserScreen
import com.pocketagent.ui.files.FileBrowserViewModel
import com.pocketagent.ui.onboarding.OnboardingEvent
import com.pocketagent.ui.onboarding.OnboardingScreen
import com.pocketagent.ui.onboarding.OnboardingViewModel
import com.pocketagent.ui.settings.SettingsScreen
import com.pocketagent.ui.settings.SettingsViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT = "chat"
    const val CHAT_WITH_ID = "chat/{conversationId}"
    const val SETTINGS = "settings"
    const val FILES = "files"

    fun chatWithId(conversationId: String) = "chat/$conversationId"
}

@Composable
fun PocketApp() {
    val nav = rememberNavController()
    val rootViewModel: RootViewModel = hiltViewModel()
    val state by rootViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        rootViewModel.bootstrap()
    }

    val ext = extendedColors()
    PocketTheme(darkTheme = state.darkTheme, focusMode = state.focusMode) {
        Surface(color = ext.bg) {
            when {
                state.loading -> {
                    // Splash screen keeps visible; nothing to render yet
                }
                else -> {
                    val startDest = if (state.onboardingComplete) Routes.CHAT else Routes.ONBOARDING
                    NavHost(navController = nav, startDestination = startDest) {
                        composable(Routes.ONBOARDING) {
                            val vm: OnboardingViewModel = hiltViewModel()
                            val s by vm.state.collectAsStateWithLifecycle()

                            // Listen for navigation events — ensures save completes before nav
                            LaunchedEffect(Unit) {
                                vm.events.collect { event ->
                                    when (event) {
                                        is OnboardingEvent.NavigateToChat -> {
                                            rootViewModel.completeOnboarding()
                                            nav.navigate(Routes.CHAT) {
                                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                                            }
                                        }
                                        is OnboardingEvent.ShowError -> {
                                            // Error is shown via state.testError, no navigation
                                        }
                                    }
                                }
                            }

                            OnboardingScreen(
                                state = s,
                                onGatewayUrlChange = vm::onGatewayUrlChange,
                                onApiKeyChange = vm::onApiKeyChange,
                                onProviderNameChange = vm::onProviderNameChange,
                                onTestConnection = vm::testConnection,
                                onSelectModel = vm::selectModel,
                                onFinish = {
                                    // CRITICAL: Actually call finish() — this saves the provider
                                    vm.finish()
                                }
                            )
                        }
                        // Chat without conversation ID — shows new/empty chat
                        composable(Routes.CHAT) {
                            val vm: ChatViewModel = hiltViewModel()
                            ChatScreen(
                                viewModel = vm,
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                                onOpenFiles = { nav.navigate(Routes.FILES) },
                                onNewConversation = {
                                    // Already on a new chat — just clear state
                                    vm.newConversation()
                                },
                                onOpenConversation = { id ->
                                    nav.navigate(Routes.chatWithId(id))
                                }
                            )
                        }
                        // Chat with specific conversation ID — loads existing
                        composable(
                            route = Routes.CHAT_WITH_ID,
                            arguments = listOf(navArgument("conversationId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val conversationId = backStackEntry.arguments?.getString("conversationId") ?: return@composable
                            val vm: ChatViewModel = hiltViewModel()
                            LaunchedEffect(conversationId) {
                                vm.loadConversation(conversationId)
                            }
                            ChatScreen(
                                viewModel = vm,
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                                onOpenFiles = { nav.navigate(Routes.FILES) },
                                onNewConversation = {
                                    // Navigate to plain CHAT route (no ID)
                                    nav.navigate(Routes.CHAT) {
                                        popUpTo(Routes.CHAT) { inclusive = true }
                                    }
                                },
                                onOpenConversation = { id ->
                                    nav.navigate(Routes.chatWithId(id))
                                }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            val vm: SettingsViewModel = hiltViewModel()
                            SettingsScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                        composable(Routes.FILES) {
                            val vm: FileBrowserViewModel = hiltViewModel()
                            FileBrowserScreen(
                                viewModel = vm,
                                onBack = { nav.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
