package com.pocketagent.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors

/**
 * v4.2: Todo list card — renders todo items like z.ai's UI.
 * Shows a card with:
 *   - Header: clipboard icon + "Todos" + count badge
 *   - List: green checkmarks for completed, circles for pending, arrows for in_progress
 *   - Collapsible (tap header to expand/collapse)
 */
@Composable
fun TodoListCard(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier
) {
    if (todos.isEmpty()) return

    val ext = extendedColors()
    var expanded by remember { mutableStateOf(true) }
    val completedCount = todos.count { it.status == "completed" }
    val totalCount = todos.size

    Surface(
        color = ext.surfaceSubtle,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, ext.divider),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clipboard icon
                Text(
                    "📋",
                    style = PocketType.BodyMedium,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Todos",
                    style = PocketType.BodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ext.textPrimary
                )
                Spacer(Modifier.size(8.dp))
                // Count badge
                Surface(
                    color = ext.accent.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "$totalCount",
                            style = PocketType.LabelSmall,
                            color = ext.accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Progress text
                Text(
                    "$completedCount/$totalCount",
                    style = PocketType.LabelSmall,
                    color = ext.textSecondary
                )
                Spacer(Modifier.size(4.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = ext.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Todo items (collapsible)
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    todos.forEach { todo ->
                        TodoItemRow(todo)
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoItemRow(todo: TodoItem) {
    val ext = extendedColors()
    val isCompleted = todo.status == "completed"
    val isInProgress = todo.status == "in_progress"

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        when {
            isCompleted -> {
                // Green circle with checkmark
                Surface(
                    color = ext.success,
                    shape = CircleShape,
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = "Completed",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
            isInProgress -> {
                // Accent circle (in progress)
                Surface(
                    color = ext.accent.copy(alpha = 0.2f),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(18.dp)
                        .border(2.dp, ext.accent, CircleShape)
                ) {}
            }
            else -> {
                // Empty circle (pending)
                Surface(
                    color = Color.Transparent,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(18.dp)
                        .border(2.dp, ext.textTertiary, CircleShape)
                ) {}
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = todo.content,
            style = PocketType.BodySmall,
            color = if (isCompleted) ext.textTertiary else ext.textPrimary,
            modifier = if (isCompleted) Modifier.alpha(0.6f) else Modifier,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

data class TodoItem(
    val content: String,
    val status: String  // pending, in_progress, completed
)
