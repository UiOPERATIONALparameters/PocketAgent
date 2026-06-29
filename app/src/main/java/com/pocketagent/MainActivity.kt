package com.pocketagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.pocketagent.ui.PocketApp
import com.pocketagent.ui.RootViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.activity.compose.setContent
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // Edge-to-edge with transparent system bars
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )

        // Keep splash visible until ViewModel signals loaded
        var keepSplash = true
        splash.setKeepOnScreenCondition { keepSplash }

        setContent {
            val rootVm: RootViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val state by rootVm.state.collectAsState()
            keepSplash = state.loading
            PocketApp()
        }
    }
}
