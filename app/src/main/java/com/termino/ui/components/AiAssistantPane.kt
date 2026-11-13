package com.termino.ui.components

import com.termino.R
import com.termino.core.reader.HighlightColor
import com.termino.core.reader.ReaderColors
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ViewSidebar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termino.ai.SourceCodec
import com.termino.ai.SourceRef
import com.termino.data.db.ChatMessageEntity
import com.termino.data.db.HighlightEntity
import com.termino.data.model.AiPaneMode
import com.termino.data.model.AiTab
import com.termino.data.model.ChatRole
import com.termino.ui.reader.ReaderUiState
import kotlinx.coroutines.delay

@Composable
fun AiAssistantPane(
    state: ReaderUiState,
    colors: ReaderColors,
    floating: Boolean,
    dragHandleModifier: Modifier = Modifier,
    onModeChange: (AiPaneMode) -> Unit,
    onTabChange: (AiTab) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInputChange: (String) -> Unit,
    onClearChat: () -> Unit,
    onAskSuggestion: (String) -> Unit,
    onOpenSource: (SourceRef) -> Unit,
    onJumpToHighlight: (HighlightEntity) -> Unit,
    onDeleteHighlight: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = if (floating) RoundedCornerShape(22.dp) else RoundedCornerShape(0.dp),
        color = colors.surface,
        contentColor = colors.foreground,
        shadowElevation = if (floating) 20.dp else 0.dp,
        border = BorderStroke(
            1.dp,
            colors.foreground.copy(alpha = if (floating) 0.10f else 0.06f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // With adjustResize + edge-to-edge the IME is reported as an inset
                // and the window is NOT resized: reserve its height here so the
                // input field and send button stay visible in the docked panel,
                // the full-screen overlay and the floating panel.
                .imePadding()
        ) {
            if (floating) {
                Box(
                    modifier = dragHandleModifier
                        .fillMaxWidth()
                        .height(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.foreground.copy(alpha = 0.18f))
                    )
                }
            }
            AssistantHeader(
                state = state,
                colors = colors,
                floating = floating,
                onModeChange = onModeChange,
                onClose = onClose,
                onOpenSettings = onOpenSettings
            )
            HorizontalDivider(color = colors.foreground.copy(alpha = 0.07f))
            AssistantTabs(
                selected = state.aiTab,
                colors = colors,
                onSelect = onTabChange
            )
            when (state.aiTab) {
                AiTab.CHAT -> ChatContent(
                    state = state,
                    colors = colors,
                    onSend = onSend,
                    onStop = onStop,
                    onInputChange = onInputChange,
                    onClearChat = onClearChat,
                    onAskSuggestion = onAskSuggestion,
                    onOpenSource = onOpenSource,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.weight(1f)
                )
                AiTab.NOTES -> NotesContent(
                    state = state,
                    colors = colors,
                    onJumpToHighlight = onJumpToHighlight,
                    onDeleteHighlight = onDeleteHighlight,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AssistantHeader(
    state: ReaderUiState,
    colors: ReaderColors,
    floating: Boolean,
    onModeChange: (AiPaneMode) -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp, top = if (floating) 2.dp else 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.assistant_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = state.book?.title ?: stringResource(R.string.assistant_subtitle_fallback),
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (floating) {
            IconButton(
                onClick = { onModeChange(AiPaneMode.DOCKED) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.ViewSidebar,
                    contentDescription = stringResource(R.string.assistant_dock_right),
                    tint = colors.foreground,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            IconButton(
                onClick = { onModeChange(AiPaneMode.FLOATING) },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Outlined.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.assistant_float),
                    tint = colors.foreground,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(
            onClick = { onModeChange(AiPaneMode.MINIMIZED) },
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = stringResource(R.string.assistant_minimize),
                tint = colors.foreground,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.assistant_settings),
                tint = colors.foreground,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = stringResource(R.string.assistant_close),
                tint = colors.foreground,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AssistantTabs(
    selected: AiTab,
    colors: ReaderColors,
    onSelect: (AiTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.background.copy(alpha = 0.55f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        AiTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) colors.foreground else colors.muted
                )
            }
        }
    }
}

@Composable
private fun ChatContent(
    state: ReaderUiState,
    colors: ReaderColors,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInputChange: (String) -> Unit,
    onClearChat: () -> Unit,
    onAskSuggestion: (String) -> Unit,
    onOpenSource: (SourceRef) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.contextSummary.ifBlank {
                    stringResource(R.string.assistant_context_default)
                },
                modifier = Modifier.weight(1f),
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (state.chatMessages.isNotEmpty()) {
                TextButton(onClick = onClearChat) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = colors.muted
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.assistant_clear), fontSize = 11.sp, color = colors.muted)
                }
            }
        }
        val listState = rememberLazyListState()
        val showStreaming = state.isStreaming || state.streamingText != null
        val itemCount = state.chatMessages.size +
            (if (showStreaming) 1 else 0) +
            (if (state.chatError != null) 1 else 0)
        LaunchedEffect(itemCount, state.streamingText, state.isStreaming) {
            if (itemCount > 0) {
                withFrameNanos { }
                if (state.isStreaming) {
                    // While streaming, the end of the last item is pinned so new
                    // text (e.g. tables) is never cut in half under the context
                    // row; scrolling is instant, with no interrupted animations
                    // leaving items clipped.
                    val layoutInfo = listState.layoutInfo
                    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
                    if (lastVisible != null && lastVisible.index >= itemCount - 1) {
                        val overshoot =
                            (lastVisible.offset + lastVisible.size) - layoutInfo.viewportEndOffset
                        if (overshoot > 0) {
                            listState.scrollBy(overshoot.toFloat())
                        }
                    } else {
                        listState.scrollToItem(itemCount - 1)
                        withFrameNanos { }
                        val corrected = listState.layoutInfo
                        val last = corrected.visibleItemsInfo.lastOrNull()
                        if (last != null && last.index >= itemCount - 1) {
                            val remaining =
                                (last.offset + last.size) - corrected.viewportEndOffset
                            if (remaining > 0) {
                                listState.scrollBy(remaining.toFloat())
                            }
                        }
                    }
                } else {
                    listState.animateScrollToItem(itemCount - 1)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (state.chatMessages.isEmpty() && !showStreaming) {
                item(key = "empty") {
                    EmptyChat(
                        colors = colors,
                        bookTitle = state.book?.title,
                        onAskSuggestion = onAskSuggestion
                    )
                }
            }
            items(state.chatMessages, key = { it.id }) { message ->
                ChatMessageBubble(
                    message = message,
                    colors = colors,
                    onOpenSource = onOpenSource
                )
            }
            if (showStreaming) {
                item(key = "streaming") {
                    StreamingBubble(
                        text = state.streamingText.orEmpty(),
                        streaming = state.isStreaming,
                        colors = colors
                    )
                }
            }
            if (state.chatError != null) {
                item(key = "error") {
                    ChatErrorCard(
                        message = state.chatError,
                        colors = colors,
                        onOpenSettings = onOpenSettings
                    )
                }
            }
        }
        ChatInput(
            value = state.chatInput,
            streaming = state.isStreaming,
            colors = colors,
            onValueChange = onInputChange,
            onSend = onSend,
            onStop = onStop
        )
    }
}

@Composable
private fun EmptyChat(
    colors: ReaderColors,
    bookTitle: String?,
    onAskSuggestion: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.assistant_empty_title),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.foreground
        )
        Text(
            text = if (bookTitle.isNullOrBlank()) {
                stringResource(R.string.assistant_empty_body)
            } else {
                stringResource(R.string.assistant_empty_body_book, bookTitle)
            },
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.muted
        )
        Spacer(Modifier.height(2.dp))
        val summary = stringResource(R.string.assistant_suggest_summary)
        val mainIdea = stringResource(R.string.assistant_suggest_main_idea)
        val searchPrompt = stringResource(R.string.assistant_suggest_search_prompt)
        SuggestionCard(
            text = summary,
            icon = Icons.Outlined.MenuBook,
            colors = colors,
            onClick = { onAskSuggestion(summary) }
        )
        SuggestionCard(
            text = mainIdea,
            icon = Icons.Outlined.Lightbulb,
            colors = colors,
            onClick = { onAskSuggestion(mainIdea) }
        )
        SuggestionCard(
            text = stringResource(R.string.assistant_suggest_search),
            icon = Icons.Outlined.Search,
            colors = colors,
            onClick = { onAskSuggestion(searchPrompt) }
        )
    }
}

@Composable
private fun SuggestionCard(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: ReaderColors,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.background.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.foreground
            )
        }
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    colors: ReaderColors,
    onOpenSource: (SourceRef) -> Unit
) {
    val isUser = ChatRole.fromStorage(message.role) == ChatRole.USER
    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                shape = RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp),
                color = colors.accent.copy(alpha = 0.13f),
                border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.16f)),
                shadowElevation = 2.dp
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    color = colors.foreground
                )
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "Término",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.muted
                )
                if (!message.model.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "· ${message.model}",
                        fontSize = 10.sp,
                        color = colors.muted.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.weight(1f))
                CopyResponseButton(content = message.content, colors = colors)
            }
            Spacer(Modifier.height(5.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp, 14.dp, 14.dp, 14.dp),
                color = colors.background.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.06f)),
                shadowElevation = 1.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    SelectionContainer {
                        MarkdownText(
                            markdown = message.content,
                            color = if (message.isError) MaterialTheme.colorScheme.error else colors.foreground,
                            fontSize = 13.5.sp,
                            lineHeight = 20.sp,
                            accentColor = colors.accent
                        )
                    }
                    val sources = SourceCodec.decode(message.sourcesJson)
                    if (sources.isNotEmpty()) {
                        Spacer(Modifier.height(9.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            sources.forEach { source ->
                                SourceChip(source = source, colors = colors, onClick = { onOpenSource(source) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CopyResponseButton(
    content: String,
    colors: ReaderColors
) {
    var copied by remember(content) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    val context = LocalContext.current
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Término", content))
                copied = true
            }
        },
        modifier = Modifier.size(30.dp)
    ) {
        Icon(
            imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
            contentDescription = if (copied) stringResource(R.string.assistant_copied) else stringResource(R.string.assistant_copy),
            tint = if (copied) colors.accent else colors.muted,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun SourceChip(
    source: SourceRef,
    colors: ReaderColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = colors.background.copy(alpha = 0.7f),
        border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.22f))
    ) {
        Row(
            modifier = Modifier.padding(start = 9.dp, end = 11.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = source.pageLabel.ifBlank { stringResource(R.string.assistant_source_fallback) },
                fontSize = 10.sp,
                color = colors.accent,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    streaming: Boolean,
    colors: ReaderColors
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(13.dp)
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text = stringResource(R.string.assistant_thinking),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.muted
            )
        }
        Spacer(Modifier.height(6.dp))
        if (text.isBlank() && streaming) {
            ReasoningCard(colors = colors)
        } else if (text.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(6.dp, 14.dp, 14.dp, 14.dp),
                color = colors.background.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.06f)),
                shadowElevation = 1.dp
            ) {
                MarkdownText(
                    markdown = text,
                    color = colors.foreground,
                    fontSize = 13.5.sp,
                    lineHeight = 20.sp,
                    streaming = streaming,
                    accentColor = colors.accent
                )
            }
        }
    }
}

@Composable
private fun ReasoningCard(colors: ReaderColors) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp, 14.dp, 14.dp, 14.dp),
        color = colors.background.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.07f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Psychology,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.assistant_reasoning),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.assistant_searching),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = colors.muted
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = colors.accent,
                trackColor = colors.foreground.copy(alpha = 0.08f)
            )
        }
    }
}

@Composable
private fun ChatErrorCard(
    message: String,
    colors: ReaderColors,
    onOpenSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.ai_response_failed),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = message,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.assistant_review_settings), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ChatInput(
    value: String,
    streaming: Boolean,
    colors: ReaderColors,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.assistant_input_placeholder),
                    fontSize = 13.sp,
                    color = colors.muted
                )
            },
            minLines = 1,
            maxLines = 5,
            shape = RoundedCornerShape(16.dp),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                color = colors.foreground
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            trailingIcon = {
                if (streaming) {
                    IconButton(onClick = onStop) {
                        Icon(
                            Icons.Outlined.StopCircle,
                            contentDescription = stringResource(R.string.assistant_stop),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = onSend,
                        enabled = value.isNotBlank()
                    ) {
                        Icon(
                            Icons.Outlined.Send,
                            contentDescription = stringResource(R.string.assistant_send),
                            tint = if (value.isNotBlank()) colors.accent else colors.muted
                        )
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent.copy(alpha = 0.65f),
                unfocusedBorderColor = colors.foreground.copy(alpha = 0.12f),
                focusedContainerColor = colors.background.copy(alpha = 0.35f),
                unfocusedContainerColor = colors.background.copy(alpha = 0.25f),
                cursorColor = colors.accent,
                focusedTextColor = colors.foreground,
                unfocusedTextColor = colors.foreground,
                focusedPlaceholderColor = colors.muted,
                unfocusedPlaceholderColor = colors.muted
            )
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.assistant_footer),
            modifier = Modifier.padding(horizontal = 4.dp),
            fontSize = 9.5.sp,
            color = colors.muted.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun NotesContent(
    state: ReaderUiState,
    colors: ReaderColors,
    onJumpToHighlight: (HighlightEntity) -> Unit,
    onDeleteHighlight: (HighlightEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val grouped = state.allHighlights
        .groupBy { it.chapterIndex }
        .toSortedMap()
    if (grouped.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Outlined.Notes,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.assistant_notes_empty_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foreground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.assistant_notes_empty_body),
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                color = colors.muted
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = 10.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        grouped.forEach { (chapterIndex, highlights) ->
            item(key = "chapter_$chapterIndex") {
                val title = state.chapters
                    .firstOrNull { it.chapterIndex == chapterIndex }
                    ?.title
                    ?: highlights.firstOrNull()?.pageLabel
                    ?: stringResource(R.string.chapter_fallback, chapterIndex + 1)
                Text(
                    text = title,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.muted
                )
            }
            items(highlights, key = { it.id }) { highlight ->
                HighlightCard(
                    highlight = highlight,
                    colors = colors,
                    onClick = { onJumpToHighlight(highlight) },
                    onDelete = { onDeleteHighlight(highlight) }
                )
            }
        }
    }
}

@Composable
private fun HighlightCard(
    highlight: HighlightEntity,
    colors: ReaderColors,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val color = when (HighlightColor.fromKey(highlight.color)) {
        HighlightColor.YELLOW -> Color(0xFFF2C14E)
        HighlightColor.GREEN -> Color(0xFF7BC47F)
        HighlightColor.BLUE -> Color(0xFF6FA8DC)
        HighlightColor.PINK -> Color(0xFFE89AB8)
        HighlightColor.PURPLE -> Color(0xFFC0A0E8)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.background.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.07f))
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = highlight.selectedText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.foreground,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = highlight.pageLabel,
                    fontSize = 9.5.sp,
                    color = colors.muted
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.assistant_delete_highlight),
                    tint = colors.muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
