package com.pocketagent.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ext = extendedColors()
    val context = LocalContext.current
    var showKey by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ext.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = ext.textPrimary
                    )
                }
                Text(
                    "Settings",
                    style = PocketType.Title,
                    color = ext.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Provider section
                Section(title = "Provider") {
                    LabeledField(
                        label = "Provider Name",
                        value = state.providerName,
                        onValueChange = viewModel::onProviderNameChange,
                        placeholder = "My Gateway"
                    )
                    LabeledField(
                        label = "Gateway URL",
                        value = state.gatewayUrl,
                        onValueChange = viewModel::onGatewayUrlChange,
                        placeholder = "https://api.gateway.orgn.com/v1",
                        keyboardType = KeyboardType.Uri
                    )
                    LabeledField(
                        label = "API Key",
                        value = state.apiKey,
                        onValueChange = viewModel::onApiKeyChange,
                        placeholder = "sk-…",
                        keyboardType = KeyboardType.Password,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Text(
                                    if (showKey) "HIDE" else "SHOW",
                                    style = PocketType.LabelSmall,
                                    color = ext.accent
                                )
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            onClick = {
                                viewModel.saveAndTest()
                            },
                            color = ext.accent,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
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
                                    Text("Testing…", style = PocketType.BodyMedium, color = ext.textOnAccent)
                                } else {
                                    Text("Save & Test", style = PocketType.BodyMedium, color = ext.textOnAccent)
                                }
                            }
                        }
                    }
                    val testResult = state.testResult
                    if (testResult != null) {
                        val isError = testResult.startsWith("Failed") || testResult.startsWith("Couldn't")
                        Surface(
                            color = if (isError) ext.error.copy(alpha = 0.1f) else ext.success.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = testResult,
                                style = PocketType.BodySmall,
                                color = if (isError) ext.error else ext.success,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // Model section
                Section(title = "Model") {
                    if (state.availableModels.isEmpty()) {
                        Text(
                            "No models loaded. Save & Test your provider first.",
                            style = PocketType.BodySmall,
                            color = ext.textSecondary,
                            modifier = Modifier.alpha(0.7f).padding(12.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            placeholder = { Text("Search ${state.availableModels.size} models…", style = PocketType.Body, color = ext.textSecondary) },
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
                        val filtered = remember(state.availableModels, search) {
                            if (search.isBlank()) state.availableModels
                            else state.availableModels.filter {
                                it.id.contains(search, ignoreCase = true) ||
                                (it.displayName?.contains(search, ignoreCase = true) ?: false)
                            }
                        }
                        LazyColumn(
                            modifier = Modifier.height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filtered, key = { it.id }) { model ->
                                val isSelected = state.activeModelId == model.id
                                Surface(
                                    onClick = {
                                        viewModel.selectModel(model.id)
                                        Toast.makeText(context, "Model selected: ${model.displayName ?: model.id}", Toast.LENGTH_SHORT).show()
                                    },
                                    color = if (isSelected) ext.accentMuted else ext.surface,
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        if (isSelected) ext.accent else ext.divider
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                model.displayName ?: model.id,
                                                style = PocketType.BodyMedium,
                                                color = ext.textPrimary
                                            )
                                            Text(
                                                model.id,
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
                                            Icon(Icons.Filled.Check, contentDescription = null, tint = ext.accent)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Agent section
                Section(title = "Agent") {
                    // System prompt
                    Text("System Prompt", style = PocketType.Label, color = ext.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.systemPrompt,
                        onValueChange = viewModel::onSystemPromptChange,
                        placeholder = {
                            Text(
                                "Leave empty to use default. Custom prompt overrides the agent's identity and behavior.",
                                style = PocketType.BodySmall,
                                color = ext.textSecondary
                            )
                        },
                        textStyle = PocketType.BodySmall.copy(color = ext.textPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 240.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ext.surface,
                            unfocusedContainerColor = ext.surface,
                            focusedBorderColor = ext.accent,
                            unfocusedBorderColor = ext.divider,
                            cursorColor = ext.accent
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    SaveButton(
                        text = "Save System Prompt",
                        onClick = {
                            viewModel.saveSystemPrompt()
                            Toast.makeText(context, "System prompt saved", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Bash timeout
                    Text("Bash Command Timeout (seconds)", style = PocketType.Label, color = ext.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.bashTimeoutSec.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { viewModel.onBashTimeoutChange(it.coerceIn(5, 300)) } },
                        textStyle = PocketType.Body.copy(color = ext.textPrimary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ext.surface,
                            unfocusedContainerColor = ext.surface,
                            focusedBorderColor = ext.accent,
                            unfocusedBorderColor = ext.divider,
                            cursorColor = ext.accent
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    SaveButton(
                        text = "Save Timeout",
                        onClick = {
                            viewModel.saveBashTimeout()
                            Toast.makeText(context, "Timeout saved: ${state.bashTimeoutSec}s", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Workspace quota
                    Text("Workspace Quota (MB)", style = PocketType.Label, color = ext.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.workspaceQuotaMb.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { viewModel.onWorkspaceQuotaChange(it.coerceIn(100, 10240)) } },
                        textStyle = PocketType.Body.copy(color = ext.textPrimary),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = ext.surface,
                            unfocusedContainerColor = ext.surface,
                            focusedBorderColor = ext.accent,
                            unfocusedBorderColor = ext.divider,
                            cursorColor = ext.accent
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    SaveButton(
                        text = "Save Quota",
                        onClick = {
                            viewModel.saveWorkspaceQuota()
                            Toast.makeText(context, "Quota saved: ${state.workspaceQuotaMb}MB", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                // Linux Environment section
                Section(title = "Linux Environment") {
                    if (state.bootstrapInstalled) {
                        // Installed state
                        Surface(
                            color = ext.success.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Full Linux installed",
                                    style = PocketType.BodyMedium,
                                    color = ext.success
                                )
                                Text(
                                    "The AI now has: bash, python3, node, git, curl, wget, apt/pkg, and more.",
                                    style = PocketType.BodySmall,
                                    color = ext.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = {
                                viewModel.uninstallBootstrap()
                                Toast.makeText(context, "Linux environment removed", Toast.LENGTH_SHORT).show()
                            },
                            color = ext.error.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Remove Linux Environment",
                                style = PocketType.BodyMedium,
                                color = ext.error,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else if (state.bootstrapInstalling) {
                        // Installing state
                        Surface(
                            color = ext.accent.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    state.bootstrapStatus,
                                    style = PocketType.BodyMedium,
                                    color = ext.accent
                                )
                                Spacer(Modifier.height(8.dp))
                                // Progress bar
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { state.bootstrapProgress },
                                    color = ext.accent,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    } else {
                        // Not installed state
                        Surface(
                            color = ext.surfaceSubtle,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Install Full Linux Environment",
                                    style = PocketType.BodyMedium,
                                    color = ext.textPrimary
                                )
                                Text(
                                    "Downloads ~50MB one-time. Gives the AI: python3, node, git, curl, wget, apt install, and hundreds of Linux packages.",
                                    style = PocketType.BodySmall,
                                    color = ext.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = { viewModel.installBootstrap() },
                            color = ext.accent,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Install Linux", style = PocketType.BodyMedium, color = ext.textOnAccent)
                            }
                        }
                        if (state.bootstrapStatus.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.bootstrapStatus,
                                style = PocketType.BodySmall,
                                color = ext.error
                            )
                        }
                    }
                }

                // About section
                Section(title = "About") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Version", style = PocketType.Body, color = ext.textSecondary)
                        Text(
                            "v${state.versionName}",
                            style = PocketType.BodyMedium,
                            color = ext.textPrimary
                        )
                    }
                    Surface(
                        onClick = {
                            viewModel.clearAllKeys()
                            Toast.makeText(context, "All keys cleared", Toast.LENGTH_SHORT).show()
                        },
                        color = ext.error.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Clear All Keys & Reset",
                            style = PocketType.BodyMedium,
                            color = ext.error,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SaveButton(text: String, onClick: () -> Unit) {
    val ext = extendedColors()
    Surface(
        onClick = onClick,
        color = ext.accent.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = ext.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(text, style = PocketType.LabelSmall, color = ext.accent)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val ext = extendedColors()
    Column {
        Text(
            title.uppercase(),
            style = PocketType.LabelSmall,
            color = ext.textSecondary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val ext = extendedColors()
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = PocketType.Label, color = ext.textSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = PocketType.Body, color = ext.textSecondary) },
            textStyle = PocketType.Body.copy(color = ext.textPrimary),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
            visualTransformation = visualTransformation,
            trailingIcon = trailingIcon,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ext.surface,
                unfocusedContainerColor = ext.surface,
                focusedBorderColor = ext.accent,
                unfocusedBorderColor = ext.divider,
                cursorColor = ext.accent
            )
        )
    }
}
