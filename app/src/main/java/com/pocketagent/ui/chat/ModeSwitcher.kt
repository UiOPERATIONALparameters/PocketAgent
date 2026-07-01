package com.pocketagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketagent.cloud.CloudState
import com.pocketagent.design.extendedColors
import com.pocketagent.design.PocketType

/**
 * v7: Modern animated mode switcher — Chat ↔ Task.
 * Pill-shaped, slides between two positions, with smooth animation.
 */
@Composable
fun ModeSwitcher(
    currentMode: CloudState.Mode,
    onModeChange: (CloudState.Mode) -> Unit,
    cloudConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val ext = extendedColors()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(ext.surfaceSubtle)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated indicator (slides between positions)
            val targetOffset = if (currentMode == CloudState.Mode.CHAT) 0f else 1f
            val animatedOffset by animateFloatAsState(
                targetValue = targetOffset,
                animationSpec = tween(durationMillis = 300),
                label = "mode_switch"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (currentMode == CloudState.Mode.CHAT) ext.accent else Color.Transparent
                    )
                    .clickable { onModeChange(CloudState.Mode.CHAT) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "💬",
                        fontSize = 14.sp
                    )
                    Text(
                        "Chat",
                        style = PocketType.Label,
                        color = if (currentMode == CloudState.Mode.CHAT) ext.textOnAccent else ext.textSecondary,
                        fontWeight = if (currentMode == CloudState.Mode.CHAT) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        if (currentMode == CloudState.Mode.TASK) ext.accent else Color.Transparent
                    )
                    .clickable { onModeChange(CloudState.Mode.TASK) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "⚡",
                        fontSize = 14.sp
                    )
                    Text(
                        "Task",
                        style = PocketType.Label,
                        color = if (currentMode == CloudState.Mode.TASK) ext.textOnAccent else ext.textSecondary,
                        fontWeight = if (currentMode == CloudState.Mode.TASK) FontWeight.SemiBold else FontWeight.Normal
                    )
                    // Connection status dot
                    if (currentMode == CloudState.Mode.TASK) {
                        Box(
                            modifier = Modifier
                                .width(6.dp)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    if (cloudConnected) ext.success else ext.warning
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Animated connection status banner.
 * Shows when cloud is connecting, disconnected, or has errors.
 */
@Composable
fun ConnectionBanner(
    status: CloudState.Status,
    lastError: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ext = extendedColors()

    AnimatedVisibility(
        visible = status != CloudState.Status.CONNECTED,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    when (status) {
                        CloudState.Status.ERROR -> ext.error.copy(alpha = 0.1f)
                        CloudState.Status.CONNECTING -> ext.warning.copy(alpha = 0.1f)
                        CloudState.Status.DISCONNECTED -> ext.error.copy(alpha = 0.1f)
                        CloudState.Status.NO_CODESPACE -> ext.warning.copy(alpha = 0.1f)
                        CloudState.Status.CONNECTED -> Color.Transparent
                    }
                )
                .clickable { if (status != CloudState.Status.CONNECTING) onRetry() }
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    when (status) {
                        CloudState.Status.ERROR -> "⚠"
                        CloudState.Status.CONNECTING -> "⟳"
                        CloudState.Status.DISCONNECTED -> "⚠"
                        CloudState.Status.NO_CODESPACE -> "⚙"
                        CloudState.Status.CONNECTED -> ""
                    },
                    fontSize = 16.sp
                )
                Column {
                    Text(
                        when (status) {
                            CloudState.Status.ERROR -> "Connection error"
                            CloudState.Status.CONNECTING -> "Connecting to cloud..."
                            CloudState.Status.DISCONNECTED -> "Cloud not connected"
                            CloudState.Status.NO_CODESPACE -> "No codespace configured"
                            CloudState.Status.CONNECTED -> ""
                        },
                        style = PocketType.BodyMedium,
                        color = ext.textPrimary
                    )
                    if (lastError != null && status == CloudState.Status.ERROR) {
                        Text(
                            lastError,
                            style = PocketType.BodySmall,
                            color = ext.textSecondary
                        )
                    }
                    if (status == CloudState.Status.NO_CODESPACE) {
                        Text(
                            "Go to Settings → Cloud to set up GitHub Codespaces",
                            style = PocketType.BodySmall,
                            color = ext.textSecondary
                        )
                    }
                }
            }
        }
    }
}
