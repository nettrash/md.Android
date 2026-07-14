/*
 * MarkdownView.kt
 * md (Android)
 *
 * Renders the block list from MarkdownParser as native Compose. Each block
 * maps to a small composable; inline spans inside a block come from
 * MarkdownInline as an AnnotatedString so Text styles them for free. Block
 * quotes recurse through BlockView. Mirrors the iOS `MarkdownView.swift`.
 */

package me.nettrash.md.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.nettrash.md.markdown.ColumnAlignment
import me.nettrash.md.markdown.ListItem
import me.nettrash.md.markdown.MarkdownBlock
import me.nettrash.md.markdown.MarkdownInline
import me.nettrash.md.markdown.MarkdownParser

/** Convenience: parse then render. The preview pane re-creates this on each
 *  keystroke; parsing is cheap. */
@Composable
fun MarkdownView(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { MarkdownParser.parse(source) }
    MarkdownBlocks(blocks, modifier)
}

@Composable
fun MarkdownBlocks(blocks: List<MarkdownBlock>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        for (block in blocks) BlockView(block)
    }
}

@Composable
private fun BlockView(block: MarkdownBlock) {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    fun inline(text: String) = MarkdownInline.annotated(text, accent, codeBg)

    when (block) {
        is MarkdownBlock.Heading -> {
            val (size, weight) = headingStyle(block.level)
            Text(
                text = inline(block.text),
                fontFamily = FontFamily.Serif,
                fontSize = size.sp,
                fontWeight = weight,
                color = if (block.level >= 6)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onBackground,
            )
        }

        is MarkdownBlock.Paragraph ->
            Text(inline(block.text), color = MaterialTheme.colorScheme.onBackground)

        is MarkdownBlock.ListBlock -> ListBlock(block.ordered, block.items, ::inline)

        is MarkdownBlock.CodeBlock -> CodeBlock(block.code)

        is MarkdownBlock.Quote -> QuoteBlock(block.blocks)

        is MarkdownBlock.Table -> TableBlock(block.header, block.alignments, block.rows, ::inline)

        MarkdownBlock.ThematicBreak ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // The author's `\newpage` — a divider marking where an exported
        // page ends. (This renderer is retired from the preview path.)
        MarkdownBlock.PageBreak ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Private author notes are never rendered.
        is MarkdownBlock.Note -> Unit
    }
}

private fun headingStyle(level: Int): Pair<Int, FontWeight> = when (level) {
    1 -> 30 to FontWeight.Bold
    2 -> 25 to FontWeight.Bold
    3 -> 21 to FontWeight.Bold
    4 -> 19 to FontWeight.SemiBold
    5 -> 17 to FontWeight.Bold
    else -> 15 to FontWeight.SemiBold
}

// MARK: - List

@Composable
private fun ListBlock(
    ordered: Boolean,
    items: List<ListItem>,
    inline: (String) -> androidx.compose.ui.text.AnnotatedString,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in items) {
            Row(
                modifier = Modifier.padding(start = (item.level * 18).dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.width(20.dp), contentAlignment = Alignment.TopEnd) {
                    Marker(ordered, item)
                }
                Text(
                    text = inline(item.text),
                    color = if (item.task == true)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onBackground,
                    textDecoration = if (item.task == true)
                        androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Composable
private fun Marker(ordered: Boolean, item: ListItem) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        item.task != null ->
            if (item.task) Icon(
                Icons.Filled.CheckBox, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            ) else Icon(
                Icons.Outlined.CheckBoxOutlineBlank, contentDescription = null, tint = muted,
            )
        ordered && item.ordinal != null ->
            Text("${item.ordinal}.", color = muted, fontFamily = FontFamily.Serif)
        else -> Text("•", color = muted)
    }
}

// MARK: - Code

@Composable
private fun CodeBlock(code: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = if (code.isEmpty()) " " else code,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Quote

@Composable
private fun QuoteBlock(blocks: List<MarkdownBlock>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    RoundedCornerShape(2.dp),
                )
        ) { Text(" ", fontSize = 1.sp) }   // give the bar height = content height
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (block in blocks) BlockView(block)
        }
    }
}

// MARK: - Table

@Composable
private fun TableBlock(
    header: List<String>,
    alignments: List<ColumnAlignment>,
    rows: List<List<String>>,
    inline: (String) -> androidx.compose.ui.text.AnnotatedString,
) {
    fun cellAlign(i: Int): TextAlign = when (alignments.getOrNull(i)) {
        ColumnAlignment.CENTER -> TextAlign.Center
        ColumnAlignment.TRAILING -> TextAlign.End
        else -> TextAlign.Start
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            header.forEachIndexed { i, cell ->
                Text(
                    text = inline(cell),
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = cellAlign(i),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (i in header.indices) {
                    Text(
                        text = inline(row.getOrNull(i) ?: ""),
                        modifier = Modifier.weight(1f),
                        textAlign = cellAlign(i),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
