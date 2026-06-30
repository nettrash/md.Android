/*
 * EditorScreen.kt
 * md (Android)
 *
 * The content of the editor window: a raw-Markdown editor and a live
 * rendered preview, with a mode switch in the app bar. On a wide window a
 * Split mode shows them side by side and the preview re-renders as you
 * type; on a narrow window the panes stack. The chosen mode is remembered
 * across configuration changes. Mirrors the iOS `DocumentView.swift`.
 *
 * Documents are opened, created and saved through the Storage Access
 * Framework (Android's document architecture). Save writes back to the
 * current URI; when there's nowhere writable yet it falls through to a
 * Create Document ("Save As") picker. The buffer is also flushed when the
 * app is backgrounded, approximating the iOS autosave.
 */

package me.nettrash.md.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.nettrash.md.DocumentViewModel

private enum class Mode { EDIT, SPLIT, PREVIEW }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: DocumentViewModel) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    var mode by rememberSaveable { mutableStateOf(Mode.SPLIT) }
    var menuOpen by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.load(it, writable = true)
        }
    }
    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.saveAs(it)
        }
    }

    fun saveOrSaveAs() {
        if (!viewModel.save()) createLauncher.launch(suggestedFileName(viewModel.displayName))
    }

    // Approximate the iOS autosave: flush a writable, dirty buffer when the
    // app goes to the background.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && viewModel.isDirty && viewModel.canWrite) {
                viewModel.save()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
                title = {
                    Text(
                        text = viewModel.displayName + if (viewModel.isDirty) " •" else "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    ModeSwitch(mode) { mode = it }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("New") }, onClick = {
                                menuOpen = false; viewModel.newDocument()
                            })
                            DropdownMenuItem(text = { Text("Open…") }, onClick = {
                                menuOpen = false
                                openLauncher.launch(arrayOf("text/markdown", "text/plain", "application/octet-stream"))
                            })
                            DropdownMenuItem(text = { Text("Save") }, onClick = {
                                menuOpen = false; saveOrSaveAs()
                            })
                            DropdownMenuItem(text = { Text("Save As…") }, onClick = {
                                menuOpen = false
                                createLauncher.launch(suggestedFileName(viewModel.displayName))
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Share Source…") }, onClick = {
                                menuOpen = false
                                Exporter.shareSource(context, viewModel.text, viewModel.displayName)
                            })
                            DropdownMenuItem(text = { Text("Print / Save as PDF…") }, onClick = {
                                menuOpen = false
                                Exporter.printRendered(context, viewModel.text, viewModel.displayName, dark)
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Content(viewModel, mode, Modifier.padding(padding))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        val items = Mode.entries.toList()
        items.forEachIndexed { index, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index, items.size),
                icon = {},
            ) {
                Icon(
                    imageVector = when (m) {
                        Mode.EDIT -> Icons.Filled.Edit
                        Mode.SPLIT -> Icons.Filled.Splitscreen
                        Mode.PREVIEW -> Icons.Filled.Visibility
                    },
                    contentDescription = m.name.lowercase().replaceFirstChar { it.uppercase() },
                )
            }
        }
    }
}

@Composable
private fun Content(viewModel: DocumentViewModel, mode: Mode, modifier: Modifier) {
    when (mode) {
        Mode.EDIT -> EditorPane(viewModel, modifier.fillMaxSize())
        Mode.PREVIEW -> PreviewPane(viewModel, modifier.fillMaxSize())
        Mode.SPLIT -> BoxWithConstraints(modifier.fillMaxSize()) {
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxSize()) {
                    EditorPane(viewModel, Modifier.weight(1f).fillMaxSize())
                    HorizontalDivider(
                        modifier = Modifier.fillMaxSize().widthIn(max = 1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    PreviewPane(viewModel, Modifier.weight(1f).fillMaxSize())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    EditorPane(viewModel, Modifier.weight(1f).fillMaxWidth())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PreviewPane(viewModel, Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun EditorPane(viewModel: DocumentViewModel, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        BasicTextField(
            value = viewModel.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxSize().imePadding().padding(16.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Serif,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.Sentences,
            ),
            decorationBox = { inner ->
                if (viewModel.text.isEmpty()) {
                    Text(
                        "# Start writing…",
                        fontFamily = FontFamily.Serif,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun PreviewPane(viewModel: DocumentViewModel, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        MarkdownView(viewModel.text, Modifier.widthIn(max = 760.dp).fillMaxWidth())
    }
}

private fun suggestedFileName(displayName: String): String {
    val base = displayName
        .removeSuffix(".md")
        .removeSuffix(".markdown")
        .ifBlank { "Untitled" }
    return "$base.md"
}
