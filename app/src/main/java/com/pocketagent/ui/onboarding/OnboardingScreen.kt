package com.pocketagent.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors
import com.pocketagent.design.softShadow

@Composable
fun OnboardingScreen(
    state: OnboardingState,
    onGatewayUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onProviderNameChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onSelectModel: (String) -> Unit,
    onFinish: () -> Unit
) {
    val ext = extendedColors()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        when (state.step) {
            OnboardingState.OnboardingStep.WELCOME -> WelcomeStep(onContinue = { /* Move to PROVIDER step */ })
            OnboardingState.OnboardingStep.PROVIDER -> ProviderStep(
                state = state,
                onProviderNameChange = onProviderNameChange,
                onGatewayUrlChange = onGatewayUrlChange,
                onApiKeyChange = onApiKeyChange,
                onTestConnection = onTestConnection
            )
            OnboardingState.OnboardingStep.MODEL -> ModelStep(
                state = state,
                onSelectModel = onSelectModel,
                onFinish = onFinish
            )
            OnboardingState.OnboardingStep.DONE -> {
                LaunchedEffect(Unit) { onFinish() }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    val ext = extendedColors()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PocketAgent",
            style = PocketType.Display.copy(fontSize = 32.sp),
            color = ext.textPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your AI agent, on your phone. Total freedom. Your keys.",
            style = PocketType.Body,
            color = ext.textSecondary,
            modifier = Modifier.alpha(0.8f)
        )
        Spacer(Modifier.height(48.dp))
        // CTA
        Surface(
            color = ext.accent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onContinue)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Get Started",
                    style = PocketType.BodyMedium,
                    color = ext.textOnAccent
                )
                Spacer(Modifier.size(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = ext.textOnAccent
                )
            }
        }
    }
}

@Composable
private fun ProviderStep(
    state: OnboardingState,
    onProviderNameChange: (String) -> Unit,
    onGatewayUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    val ext = extendedColors()
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Connect your model",
            style = PocketType.Headline,
            color = ext.textPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Bring your own key. Choose any OpenAI-compatible gateway.",
            style = PocketType.BodySmall,
            color = ext.textSecondary,
            modifier = Modifier.alpha(0.8f)
        )
        Spacer(Modifier.height(32.dp))

        // Provider name
        Text("Provider Name", style = PocketType.Label, color = ext.textSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.providerName,
            onValueChange = onProviderNameChange,
            placeholder = { Text("My Gateway", style = PocketType.Body, color = ext.textSecondary) },
            textStyle = PocketType.Body.copy(color = ext.textPrimary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.surface,
                unfocusedContainerColor = ext.surface,
                focusedBorderColor = ext.accent,
                unfocusedBorderColor = ext.divider,
                cursorColor = ext.accent
            )
        )

        Spacer(Modifier.height(16.dp))

        // Gateway URL
        Text("Gateway URL", style = PocketType.Label, color = ext.textSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.gatewayUrl,
            onValueChange = onGatewayUrlChange,
            placeholder = { Text("https://api.gateway.orgn.com/v1", style = PocketType.Body, color = ext.textSecondary) },
            textStyle = PocketType.Body.copy(color = ext.textPrimary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.surface,
                unfocusedContainerColor = ext.surface,
                focusedBorderColor = ext.accent,
                unfocusedBorderColor = ext.divider,
                cursorColor = ext.accent
            )
        )

        Spacer(Modifier.height(16.dp))

        // API key
        Text("API Key", style = PocketType.Label, color = ext.textSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            placeholder = { Text("sk-…", style = PocketType.Body, color = ext.textSecondary) },
            textStyle = PocketType.Body.copy(color = ext.textPrimary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Text(
                        if (showKey) "HIDE" else "SHOW",
                        style = PocketType.LabelSmall,
                        color = ext.accent
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.surface,
                unfocusedContainerColor = ext.surface,
                focusedBorderColor = ext.accent,
                unfocusedBorderColor = ext.divider,
                cursorColor = ext.accent
            )
        )

        Spacer(Modifier.height(24.dp))

        // Test button
        Surface(
            color = ext.accent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onTestConnection)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.testing) {
                    CircularProgressIndicator(
                        color = ext.textOnAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Connecting…", style = PocketType.BodyMedium, color = ext.textOnAccent)
                } else {
                    Text("Connect", style = PocketType.BodyMedium, color = ext.textOnAccent)
                }
            }
        }

        // Error
        if (state.testError != null) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = ext.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = state.testError,
                    style = PocketType.BodySmall,
                    color = ext.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ModelStep(
    state: OnboardingState,
    onSelectModel: (String) -> Unit,
    onFinish: () -> Unit
) {
    val ext = extendedColors()
    var search by remember { mutableStateOf("") }
    val filteredModels = remember(state.models, search) {
        if (search.isBlank()) state.models
        else state.models.filter {
            it.id.contains(search, ignoreCase = true) ||
            (it.displayName?.contains(search, ignoreCase = true) ?: false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Pick a model",
            style = PocketType.Headline,
            color = ext.textPrimary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Found ${state.models.size} models on your gateway.",
            style = PocketType.BodySmall,
            color = ext.textSecondary,
            modifier = Modifier.alpha(0.8f)
        )

        Spacer(Modifier.height(16.dp))

        // Error display (if save failed)
        if (state.testError != null) {
            Surface(
                color = ext.error.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    text = state.testError,
                    style = PocketType.BodySmall,
                    color = ext.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Search bar
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Search models…", style = PocketType.Body, color = ext.textSecondary) },
            textStyle = PocketType.Body.copy(color = ext.textPrimary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null, tint = ext.textSecondary)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.surface,
                unfocusedContainerColor = ext.surface,
                focusedBorderColor = ext.accent,
                unfocusedBorderColor = ext.divider,
                cursorColor = ext.accent
            )
        )

        Spacer(Modifier.height(12.dp))

        // Model list
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredModels, key = { it.id }) { model ->
                val isSelected = state.selectedModelId == model.id
                Surface(
                    color = if (isSelected) ext.accentMuted else ext.surface,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSelected) ext.accent else ext.divider
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onSelectModel(model.id) })
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName ?: model.id,
                                style = PocketType.BodyMedium,
                                color = ext.textPrimary
                            )
                            Text(
                                text = model.id,
                                style = PocketType.CodeSmall,
                                color = ext.textSecondary,
                                modifier = Modifier.alpha(0.7f)
                            )
                            if (model.supportsVision) {
                                Text(
                                    text = "vision",
                                    style = PocketType.LabelSmall,
                                    color = ext.accent,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = ext.accent
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Finish button — shows saving indicator while saving
        val canFinish = state.selectedModelId != null && !state.saving
        Surface(
            color = if (canFinish) ext.accent else ext.surfaceSubtle,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = { if (canFinish) onFinish() })
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.saving) {
                    CircularProgressIndicator(
                        color = ext.textOnAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Saving…", style = PocketType.BodyMedium, color = ext.textOnAccent)
                } else {
                    Text(
                        "Start using PocketAgent",
                        style = PocketType.BodyMedium,
                        color = if (state.selectedModelId != null) ext.textOnAccent else ext.textTertiary
                    )
                }
            }
        }
    }
}


