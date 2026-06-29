package com.pocketagent.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
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
import com.pocketagent.ui.onboarding.OnboardingScreen
import com.pocketagent.ui.onboarding.OnboardingViewModel
import com.pocketagent.ui.settings.SettingsScreen
import com.pocketagent.ui.settings.SettingsViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHAT_LIST = "chat_list"
    const val CHAT = "chat/{conversationId}"
    const val SETTINGS = "settings"

    fun chat(conversationId: String) = "chat/$conversationId"
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
    PocketTheme(darkTheme = state.darkTheme) {
        Surface(color = ext.bg) {
            when {
                state.loading -> {
                    // Splash screen keeps visible; nothing to render yet
                }
                else -> {
                    val startDest = if (state.onboardingComplete) Routes.CHAT_LIST else Routes.ONBOARDING
                    NavHost(navController = nav, startDestination = startDest) {
                        composable(Routes.ONBOARDING) {
                            val vm: OnboardingViewModel = hiltViewModel()
                            val s by vm.state.collectAsStateWithLifecycle()
                            OnboardingScreen(
                                state = s,
                                onGatewayUrlChange = vm::onGatewayUrlChange,
                                onApiKeyChange = vm::onApiKeyChange,
                                onProviderNameChange = vm::onProviderNameChange,
                                onTestConnection = vm::testConnection,
                                onSelectModel = vm::selectModel,
                                onFinish = {
                                    rootViewModel.completeOnboarding()
                                    nav.navigate(Routes.CHAT_LIST) {
                                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(Routes.CHAT_LIST) {
                            val vm: ChatViewModel = hiltViewModel()
                            ChatScreen(
                                viewModel = vm,
                                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                                onNewConversation = { id ->
                                    nav.navigate(Routes.chat(id))
                                },
                                onOpenConversation = { id ->
                                    nav.navigate(Routes.chat(id))
                                }
                            )
                        }
                        composable(
                            route = Routes.CHAT,
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
                                onNewConversation = { id ->
                                    nav.navigate(Routes.chat(id)) {
                                        popUpTo(Routes.CHAT_LIST)
                                    }
                                },
                                onOpenConversation = { id ->
                                    nav.navigate(Routes.chat(id))
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
                    }
                }
            }
        }
    }
}
