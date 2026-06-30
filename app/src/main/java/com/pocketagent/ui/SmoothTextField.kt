package com.pocketagent.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors

/**
 * v3.3: A text field that doesn't cause ViewModel recomposition on every keystroke.
 *
 * The standard pattern of `value = state.field, onValueChange = vm::update` causes the
 * entire screen to recompose on every keystroke because the value comes from a StateFlow.
 *
 * This composable uses local state that updates immediately (no lag), and only pushes
 * to the ViewModel when the field loses focus. This eliminates the typing glitch.
 *
 * Usage:
 *   SmoothTextField(
 *       value = state.gatewayUrl,
 *       onValueChange = vm::onGatewayUrlChange,
 *       placeholder = "https://api.openai.com/v1"
 *   )
 */
@Composable
fun SmoothTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val ext = extendedColors()

    // Local state — updates immediately on every keystroke (no lag)
    var localValue by remember(value, placeholder) { mutableStateOf(value) }
    var isFocused by remember { mutableStateOf(false) }

    // Sync external value changes to local (when ViewModel loads data)
    LaunchedEffect(value) {
        if (!isFocused) {
            localValue = value
        }
    }

    OutlinedTextField(
        value = localValue,
        onValueChange = { newValue ->
            localValue = newValue  // ONLY update local — no ViewModel call during typing
        },
        placeholder = { Text(placeholder, style = PocketType.Body, color = ext.textSecondary) },
        textStyle = PocketType.Body.copy(color = ext.textPrimary),
        singleLine = singleLine,
        modifier = modifier
            .onFocusChanged { focusState ->
                val wasFocused = isFocused
                isFocused = focusState.isFocused
                // Push to ViewModel ONLY when losing focus
                if (wasFocused && !focusState.isFocused) {
                    onValueChange(localValue)
                }
            },
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = keyboardType,
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
