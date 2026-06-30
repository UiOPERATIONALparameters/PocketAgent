package com.pocketagent.ui.files

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ext = extendedColors()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = ext.textPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Agent Workspace",
                        style = PocketType.Title,
                        color = ext.textPrimary
                    )
                    Text(
                        state.currentPath.let { if (it == ".") "~" else "~/$it" },
                        style = PocketType.CodeSmall,
                        color = ext.textSecondary,
                        modifier = Modifier.alpha(0.7f)
                    )
                }
                // Up button
                IconButton(onClick = viewModel::navigateUp) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Up", tint = ext.textPrimary)
                }
            }

            // Storage usage bar
            val usedMb = state.totalUsedBytes / (1024 * 1024)
            val pct = (state.totalUsedBytes.toFloat() / (state.quotaMb * 1024 * 1024)).coerceIn(0f, 1f)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Storage",
                        style = PocketType.Label,
                        color = ext.textSecondary
                    )
                    Text(
                        "$usedMb MB / ${state.quotaMb} MB",
                        style = PocketType.Label,
                        color = ext.textSecondary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(ext.divider, RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct)
                            .height(4.dp)
                            .background(ext.accent, RoundedCornerShape(2.dp))
                    )
                }
            }

            // File list
            if (state.entries.isEmpty() && state.previewContent == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Empty directory",
                            style = PocketType.Body,
                            color = ext.textSecondary
                        )
                        Text(
                            "Ask the agent to create something",
                            style = PocketType.BodySmall,
                            color = ext.textTertiary,
                            modifier = Modifier.alpha(0.7f).padding(top = 4.dp)
                        )
                    }
                }
            } else if (state.previewContent != null) {
                // Preview pane
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = ext.surface,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                state.previewName ?: "Preview",
                                style = PocketType.BodyMedium,
                                color = ext.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = viewModel::closePreview) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = ext.textSecondary)
                            }
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        item {
                            Surface(
                                color = ext.codeBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    state.previewContent ?: "",
                                    style = PocketType.Code.copy(color = ext.codeText),
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(state.entries, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = {
                                    if (entry.isDirectory) viewModel.navigateTo(entry.path)
                                    else viewModel.previewFile(entry.path)
                                })
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (entry.isDirectory) ext.accent else ext.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    style = PocketType.BodyMedium,
                                    color = ext.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!entry.isDirectory) {
                                    Text(
                                        "${entry.size} bytes • ${dateFormat.format(Date(entry.modified))}",
                                        style = PocketType.CodeSmall,
                                        color = ext.textTertiary,
                                        modifier = Modifier.alpha(0.7f)
                                    )
                                } else {
                                    Text(
                                        dateFormat.format(Date(entry.modified)),
                                        style = PocketType.CodeSmall,
                                        color = ext.textTertiary,
                                        modifier = Modifier.alpha(0.7f)
                                    )
                                }
                            }
                            if (!entry.isDirectory) {
                                // Download/share button
                                IconButton(onClick = {
                                    val filePath = viewModel.downloadFile(entry.path)
                                    if (filePath != null) {
                                        val file = java.io.File(filePath)
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = context.contentResolver.getType(uri) ?: "*/*"
                                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        val chooser = android.content.Intent.createChooser(intent, "Download ${entry.name}")
                                        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(chooser)
                                    } else {
                                        Toast.makeText(context, "Cannot download file", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = "Download",
                                        tint = ext.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                // Delete button
                                IconButton(onClick = { viewModel.deleteFile(entry.path) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete",
                                        tint = ext.textTertiary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Error
            state.error?.let { err ->
                Surface(
                    color = ext.error.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Text(
                        err,
                        style = PocketType.BodySmall,
                        color = ext.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}


