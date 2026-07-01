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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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

                    Spacer(Modifier.height(12.dp))

                    // Max tool iterations
                    Text("Max Tool Iterations (per turn)", style = PocketType.Label, color = ext.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = state.maxToolIterations.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { viewModel.onMaxIterationsChange(it.coerceIn(5, 100)) } },
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
                        text = "Save Iterations",
                        onClick = {
                            viewModel.saveMaxIterations()
                            Toast.makeText(context, "Max iterations saved: ${state.maxToolIterations}", Toast.LENGTH_SHORT).show()
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    // Token Save Mode toggle
                    Surface(
                        color = ext.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ext.divider),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Token Save Mode",
                                    style = PocketType.BodyMedium,
                                    color = ext.textPrimary
                                )
                                Text(
                                    "Truncates tool results more aggressively and skips reasoning. Saves tokens for long sessions. Tools still work.",
                                    style = PocketType.BodySmall,
                                    color = ext.textSecondary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            androidx.compose.material3.Switch(
                                checked = state.tokenSaveMode,
                                onCheckedChange = { enabled ->
                                    viewModel.onTokenSaveModeChange(enabled)
                                    viewModel.saveTokenSaveMode()
                                    Toast.makeText(
                                        context,
                                        if (enabled) "Token save mode ON — results truncated, reasoning hidden" else "Token save mode OFF — full output",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedThumbColor = ext.accent,
                                    checkedTrackColor = ext.accent.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }

                // v6: Termux Bridge section
                Section(title = "Termux Connection") {
                    if (state.termuxConnected) {
                        // Connected state
                        Surface(
                            color = ext.success.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Connected to Termux ✓",
                                    style = PocketType.BodyMedium,
                                    color = ext.success
                                )
                                state.termuxVersion?.let {
                                    Text(
                                        "Daemon: v$it",
                                        style = PocketType.BodySmall,
                                        color = ext.textSecondary,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                state.termuxUser?.let {
                                    Text(
                                        "User: $it",
                                        style = PocketType.BodySmall,
                                        color = ext.textSecondary
                                    )
                                }
                                Text(
                                    "The AI has full access to your Termux environment — same packages, same \${'$'}PATH, same git config.",
                                    style = PocketType.BodySmall,
                                    color = ext.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            onClick = { viewModel.refreshTermuxConnection() },
                            color = ext.surfaceSubtle,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Refresh Connection",
                                style = PocketType.BodyMedium,
                                color = ext.textPrimary,
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        // Disconnected state
                        Surface(
                            color = ext.error.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Termux not connected",
                                    style = PocketType.BodyMedium,
                                    color = ext.error
                                )
                                Text(
                                    "To enable bash commands, install Termux from F-Droid and run the installer:",
                                    style = PocketType.BodySmall,
                                    color = ext.textSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    color = ext.surfaceSubtle,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        "curl -sL https://raw.githubusercontent.com/UiOPERATIONALparameters/PocketAgent/v6-termux-bridge/termux-daemon/install.sh | bash",
                                        style = PocketType.CodeSmall,
                                        color = ext.textPrimary,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Termux Token",
                        style = PocketType.Label,
                        color = ext.textSecondary
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = state.termuxToken,
                        onValueChange = { viewModel.onTermuxTokenChange(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Paste token from ~/.pocketagent/token") },
                        textStyle = PocketType.BodyMedium,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = ext.textPrimary,
                            unfocusedTextColor = ext.textPrimary,
                            focusedBorderColor = ext.accent,
                            unfocusedBorderColor = ext.divider
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        onClick = { viewModel.saveTermuxToken() },
                        color = ext.accent,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Save Token & Connect",
                            style = PocketType.BodyMedium,
                            color = ext.textOnAccent,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                // v6: Agent Capabilities section
                Section(title = "Agent Capabilities") {
                    // Auto-compact threshold
                    Text(
                        "Auto-compact threshold: ${(state.autoCompactThreshold * 100).toInt()}%",
                        style = PocketType.BodyMedium,
                        color = ext.textPrimary
                    )
                    Text(
                        "Conversation is auto-summarized when it reaches this % of the model's context window.",
                        style = PocketType.BodySmall,
                        color = ext.textSecondary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                    androidx.compose.material3.Slider(
                        value = state.autoCompactThreshold,
                        onValueChange = { viewModel.onAutoCompactThresholdChange(it) },
                        valueRange = 0.5f..0.9f,
                        steps = 7,
                        onValueChangeFinished = { viewModel.saveAutoCompactThreshold() },
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = ext.accent,
                            activeTrackColor = ext.accent
                        )
                    )

                    Spacer(Modifier.height(8.dp))
                    // Subagents toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Enable subagents (task tool)",
                                style = PocketType.BodyMedium,
                                color = ext.textPrimary
                            )
                            Text(
                                "Allow the AI to spawn subagents for delegated work.",
                                style = PocketType.BodySmall,
                                color = ext.textSecondary
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = state.enableSubagents,
                            onCheckedChange = {
                                viewModel.onEnableSubagentsChange(it)
                                viewModel.saveEnableSubagents()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = ext.accent,
                                checkedTrackColor = ext.accent.copy(alpha = 0.3f)
                            )
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    // Focus mode toggle (eink aesthetic)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Focus mode (eink aesthetic)",
                                style = PocketType.BodyMedium,
                                color = ext.textPrimary
                            )
                            Text(
                                "Reduces contrast and decoration for a calmer reading experience.",
                                style = PocketType.BodySmall,
                                color = ext.textSecondary
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = state.focusMode,
                            onCheckedChange = {
                                viewModel.onFocusModeChange(it)
                                viewModel.saveFocusMode()
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = ext.accent,
                                checkedTrackColor = ext.accent.copy(alpha = 0.3f)
                            )
                        )
                    }
                }

                // Advanced section (v4.5: collapsed by default)
                var showAdvanced by remember { mutableStateOf(false) }
                Surface(
                    onClick = { showAdvanced = !showAdvanced },
                    color = ext.surfaceSubtle,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Advanced Settings", style = PocketType.BodyMedium, color = ext.textPrimary, modifier = Modifier.weight(1f))
                        Icon(imageVector = if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = ext.textTertiary)
                    }
                }
                if (showAdvanced) {
                    Section(title = "Skills") {
                        val allSkills = listOf("build-website" to "Build websites", "build-apk" to "Build APKs", "research-topic" to "Research", "write-script" to "Write scripts", "make-chart" to "Charts", "debug-code" to "Debug", "summarize-document" to "Summarize", "convert-file" to "Convert", "data-analysis" to "Analyze", "file-management" to "Files", "install-java" to "JDK")
                        val disabled = remember(state.disabledSkills) { state.disabledSkills.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() }
                        Text("Toggle skills on/off to save tokens.", style = PocketType.BodySmall, color = ext.textSecondary, modifier = Modifier.padding(bottom = 8.dp))
                        allSkills.forEach { (id, name) ->
                            val enabled = id !in disabled
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = PocketType.BodyMedium, color = if (enabled) ext.textPrimary else ext.textTertiary, modifier = Modifier.weight(1f))
                                androidx.compose.material3.Switch(checked = enabled, onCheckedChange = { c -> val cur = disabled.toMutableSet(); if (!c) cur.add(id) else cur.remove(id); viewModel.onDisabledSkillsChange(cur.joinToString(",")) }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = ext.accent, checkedTrackColor = ext.accent.copy(alpha = 0.3f)))
                            }
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
    // v3.3 REAL FIX: Don't push to ViewModel on every keystroke — that causes the entire
    // Settings screen to recompose, creating visible lag/glitch.
    // Instead: update local state immediately (no lag), push to ViewModel ONLY when focus is lost.
    var localValue by remember(label) { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }

    // Sync external value changes to local (e.g., when ViewModel loads data initially)
    // Only do this when NOT focused — otherwise we'd overwrite what the user is typing
    androidx.compose.runtime.LaunchedEffect(value) {
        if (!isFocused) {
            localValue = value
        }
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = PocketType.Label, color = ext.textSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = localValue,
            onValueChange = { newValue ->
                localValue = newValue  // update local immediately (smooth display)
                onValueChange(newValue)  // push to ViewModel (so buttons work)
            },
            placeholder = { Text(placeholder, style = PocketType.Body, color = ext.textSecondary) },
            textStyle = PocketType.Body.copy(color = ext.textPrimary),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    val wasFocused = isFocused
                    isFocused = focusState.isFocused
                    // When LOSING focus, push the final value to the ViewModel
                    if (wasFocused && !focusState.isFocused) {
                        onValueChange(localValue)
                    }
                },
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
