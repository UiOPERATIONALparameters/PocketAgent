package com.pocketagent.ui.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pocketagent.design.PocketType
import com.pocketagent.design.extendedColors

/**
 * Simple markdown renderer for agent responses.
 * Supports:
 * - Code blocks (```language\n...\n```)
 * - Inline code (`code`)
 * - Bold (**text**)
 * - Headers (# ## ###)
 * - Bullet lists (- item)
 * - Numbered lists (1. item)
 *
 * Not a full markdown parser — just enough to make agent responses readable.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier
) {
    val ext = extendedColors()
    val context = LocalContext.current
    val blocks = remember(text) { parseMarkdown(text) }

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.CodeBlock -> {
                    // Code block with copy button
                    Surface(
                        color = ext.codeBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = block.language.ifEmpty { "code" },
                                    style = PocketType.LabelSmall,
                                    color = ext.textTertiary
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("code", block.code))
                                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = ext.textTertiary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Text(
                                text = block.code,
                                style = PocketType.Code.copy(color = ext.codeText),
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
                is MarkdownBlock.Header -> {
                    val style = when (block.level) {
                        1 -> PocketType.Display
                        2 -> PocketType.Headline
                        else -> PocketType.Title
                    }
                    Text(
                        text = block.text,
                        style = style.copy(color = ext.textPrimary),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                is MarkdownBlock.BulletList -> {
                    block.items.forEach { item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "•",
                                style = PocketType.Body.copy(color = ext.textSecondary),
                                modifier = Modifier.width(16.dp)
                            )
                            Text(
                                text = parseInlineMarkdown(item, ext),
                                style = PocketType.Body.copy(color = ext.textPrimary)
                            )
                        }
                    }
                }
                is MarkdownBlock.NumberedList -> {
                    block.items.forEachIndexed { idx, item ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = "${idx + 1}.",
                                style = PocketType.Body.copy(color = ext.textSecondary),
                                modifier = Modifier.width(20.dp)
                            )
                            Text(
                                text = parseInlineMarkdown(item, ext),
                                style = PocketType.Body.copy(color = ext.textPrimary)
                            )
                        }
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, ext),
                        style = PocketType.Body.copy(color = ext.textPrimary),
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private sealed class MarkdownBlock {
    data class CodeBlock(val code: String, val language: String) : MarkdownBlock()
    data class Header(val text: String, val level: Int) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class BulletList(val items: List<String>) : MarkdownBlock()
    data class NumberedList(val items: List<String>) : MarkdownBlock()
}

private fun parseMarkdown(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = text.lines()
    var i = 0
    val paragraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
            paragraphLines.clear()
        }
    }

    while (i < lines.size) {
        val line = lines[i]

        // Code block
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val language = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            i++ // skip closing ```
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n"), language))
            continue
        }

        // Headers
        val headerMatch = Regex("^(#{1,6})\\s+(.+)").matchEntire(line)
        if (headerMatch != null) {
            flushParagraph()
            val level = headerMatch.groupValues[1].length
            val headerText = headerMatch.groupValues[2]
            blocks.add(MarkdownBlock.Header(headerText, level))
            i++
            continue
        }

        // Bullet list
        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            flushParagraph()
            val items = mutableListOf<String>()
            while (i < lines.size && (lines[i].trimStart().startsWith("- ") || lines[i].trimStart().startsWith("* "))) {
                items.add(lines[i].trimStart().removePrefix("- ").removePrefix("* "))
                i++
            }
            blocks.add(MarkdownBlock.BulletList(items))
            continue
        }

        // Numbered list
        if (Regex("^\\d+\\.\\s+").matches(line.trimStart())) {
            flushParagraph()
            val items = mutableListOf<String>()
            while (i < lines.size && Regex("^\\d+\\.\\s+").matches(lines[i].trimStart())) {
                items.add(lines[i].trimStart().substringAfter(". "))
                i++
            }
            blocks.add(MarkdownBlock.NumberedList(items))
            continue
        }

        // Empty line — paragraph break
        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }

        // Regular text
        paragraphLines.add(line)
        i++
    }

    flushParagraph()
    return blocks
}

private fun parseInlineMarkdown(text: String, ext: com.pocketagent.design.PocketExtendedColors): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            // Inline code
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(ext.codeBg.value))) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            // Bold
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            // Italic
            if (text[i] == '_' && i + 1 < text.length && text[i + 1] != '_') {
                val end = text.indexOf('_', i + 1)
                if (end > i && end < text.length) {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            append(text[i])
            i++
        }
    }
}
