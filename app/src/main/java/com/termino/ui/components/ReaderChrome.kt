package com.termino.ui.components

import com.termino.core.parser.TocItem
import com.termino.core.reader.HighlightColor
import com.termino.core.reader.ReaderColors
import com.termino.core.reader.ReaderReference
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import android.util.Log
import com.termino.ai.SourceRef
import com.termino.data.db.ChapterSummary
import com.termino.data.db.HighlightEntity
import com.termino.data.model.AiPaneMode
import com.termino.data.model.AiTab
import com.termino.data.model.QuickActionType
import com.termino.ui.reader.ReaderUiState
import com.termino.ui.reader.QuickActionUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun ReaderPageBar(
    state: ReaderUiState,
    colors: ReaderColors,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreviousChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onOpenToc: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canPrevChapter = state.currentChapterIndex > 0
    val canNextChapter = state.currentChapterIndex < state.chapters.size - 1
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = colors.surface.copy(alpha = 0.94f),
        contentColor = colors.foreground,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPreviousChapter,
                enabled = canPrevChapter,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Outlined.SkipPrevious,
                    contentDescription = "Capítulo anterior",
                    tint = if (canPrevChapter) colors.foreground else colors.muted.copy(alpha = 0.35f),
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.KeyboardArrowLeft,
                    contentDescription = "Página anterior",
                    tint = colors.foreground,
                    modifier = Modifier.size(20.dp)
                )
            }
            val isPdf = state.book?.format.equals("PDF", true)
            Text(
                text = if (isPdf) {
                    "${state.currentChapterIndex + 1} / ${state.chapters.size.coerceAtLeast(1)}"
                } else {
                    "${state.pageIndex + 1} / ${state.totalPages.coerceAtLeast(1)}"
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenToc)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.foreground
            )
            IconButton(onClick = onNext, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.KeyboardArrowRight,
                    contentDescription = "Página siguiente",
                    tint = colors.foreground,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = onNextChapter,
                enabled = canNextChapter,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Outlined.SkipNext,
                    contentDescription = "Capítulo siguiente",
                    tint = if (canNextChapter) colors.foreground else colors.muted.copy(alpha = 0.35f),
                    modifier = Modifier.size(19.dp)
                )
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 6.dp)
                    .width(1.dp)
                    .height(18.dp)
                    .background(colors.foreground.copy(alpha = 0.12f))
            )
            Text(
                text = state.currentChapter?.title ?: state.book?.title ?: "",
                modifier = Modifier
                    .widthIn(min = 60.dp, max = 220.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onOpenToc)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                fontSize = 11.sp,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ReaderProgressLine(
    progress: Float,
    colors: ReaderColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.foreground.copy(alpha = 0.06f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(2.dp)
                .background(colors.accent)
        )
    }
}

@Composable
fun ReaderTocSheet(
    state: ReaderUiState,
    colors: ReaderColors,
    onDismiss: () -> Unit,
    onSelectChapter: (Int) -> Unit,
    onSelectTocItem: (TocItem) -> Unit = {},
    onSelectHighlight: (HighlightEntity) -> Unit = {},
    onSelectBookmark: (com.termino.data.db.BookmarkEntity) -> Unit = {},
    onDeleteBookmark: (com.termino.data.db.BookmarkEntity) -> Unit = {}
) {
    val isPdf = state.book?.format.equals("PDF", true)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selecting by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    val selectChapter: (Int) -> Unit = { chapterIndex ->
        if (!selecting) {
            selecting = true
            onSelectChapter(chapterIndex)
        }
    }

    val selectToc: (TocItem) -> Unit = { item ->
        if (!selecting) {
            selecting = true
            onSelectTocItem(item)
        }
    }

    val selectHighlight: (HighlightEntity) -> Unit = { highlight ->
        if (!selecting) {
            selecting = true
            onSelectHighlight(highlight)
        }
    }

    val chapterListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.chapters.indexOfFirst {
            it.chapterIndex == state.currentChapterIndex
        }.coerceAtLeast(0)
    )

    val tocListState = rememberLazyListState(
        initialFirstVisibleItemIndex = state.tocItems.indexOfFirst {
            it.chapterIndex == state.currentChapterIndex
        }.coerceAtLeast(0)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.foreground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.foreground.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = colors.accent
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.book?.title ?: "Navegación",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isPdf) {
                            "${state.chapters.size} páginas"
                        } else {
                            "${state.chapters.size} capítulos • ${state.tocItems.size} secciones"
                        },
                        fontSize = 11.sp,
                        color = colors.muted
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Cerrar contenido",
                        tint = colors.muted
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Pestañas estilo Moon+ Reader: Índice (TOC) / Capítulos / Subrayados
            val tabs = listOf(
                "Índice" to (state.tocItems.size.takeIf { it > 0 } ?: state.chapters.size),
                "Capítulos" to state.chapters.size,
                "Notas" to state.allHighlights.size,
                "Marcas" to state.bookmarks.size
            )
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = colors.surface,
                contentColor = colors.accent,
                divider = { HorizontalDivider(color = colors.foreground.copy(alpha = 0.08f)) }
            ) {
                tabs.forEachIndexed { index, (label, count) ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = label,
                                    fontSize = 12.5.sp,
                                    maxLines = 1,
                                    fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selectedTab == index) colors.accent else colors.muted
                                )
                                Text(
                                    text = "$count",
                                    fontSize = 10.sp,
                                    color = colors.muted.copy(alpha = 0.8f)
                                )
                            }
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> {
                    if (state.tocItems.isNotEmpty()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar sección o tema…", fontSize = 12.sp, color = colors.muted) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = colors.muted,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = "Limpiar búsqueda",
                                            tint = colors.muted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accent,
                                unfocusedBorderColor = colors.foreground.copy(alpha = 0.12f),
                                focusedTextColor = colors.foreground,
                                unfocusedTextColor = colors.foreground
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(bottom = 6.dp)
                        )

                        val filteredItems = remember(searchQuery, state.tocItems) {
                            if (searchQuery.isBlank()) state.tocItems
                            else state.tocItems.filter { it.title.contains(searchQuery, ignoreCase = true) }
                        }

                        LazyColumn(
                            state = tocListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp)
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(filteredItems) { item ->
                                val isCurrentChapter = item.chapterIndex == state.currentChapterIndex
                                TocHierarchyRow(
                                    item = item,
                                    isCurrentChapter = isCurrentChapter,
                                    colors = colors,
                                    onClick = { selectToc(item) }
                                )
                            }
                        }
                    } else {
                        // Fallback si el EPUB no tiene navigation.xhtml ni toc.ncx
                        LazyColumn(
                            state = chapterListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp)
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(state.chapters, key = { it.chapterIndex }) { chapter ->
                                val selected = chapter.chapterIndex == state.currentChapterIndex
                                TocChapterRow(
                                    number = chapterDisplayNumber(chapter),
                                    title = chapter.title,
                                    subtitle = chapter.pageLabel,
                                    wordCount = chapter.wordCount,
                                    selected = selected,
                                    colors = colors,
                                    onClick = { selectChapter(chapter.chapterIndex) }
                                )
                            }
                        }
                    }
                }
                1 -> {
                    // TAB 1: LISTA PLANA DE CAPÍTULOS (SPINE)
                    LazyColumn(
                        state = chapterListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(state.chapters, key = { it.chapterIndex }) { chapter ->
                            val selected = chapter.chapterIndex == state.currentChapterIndex
                            TocChapterRow(
                                number = chapterDisplayNumber(chapter),
                                title = chapter.title,
                                subtitle = chapter.pageLabel,
                                wordCount = chapter.wordCount,
                                selected = selected,
                                colors = colors,
                                onClick = { selectChapter(chapter.chapterIndex) }
                            )
                        }
                    }
                }
                3 -> BookmarkList(
                    bookmarks = state.bookmarks,
                    chapters = state.chapters,
                    colors = colors,
                    onOpen = onSelectBookmark,
                    onDelete = onDeleteBookmark
                )
                2 -> {
                    // TAB 2: SUBRAYADOS Y NOTAS
                    if (state.allHighlights.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Aún no tienes notas ni subrayados en este libro.",
                                fontSize = 12.5.sp,
                                color = colors.muted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp)
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.allHighlights, key = { it.id }) { highlight ->
                                val chapterTitle = state.chapters
                                    .firstOrNull { it.chapterIndex == highlight.chapterIndex }?.title
                                    ?: "Capítulo ${highlight.chapterIndex + 1}"
                                TocHighlightRow(
                                    highlight = highlight,
                                    chapterTitle = chapterTitle,
                                    colors = colors,
                                    onClick = { selectHighlight(highlight) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocHierarchyRow(
    item: TocItem,
    isCurrentChapter: Boolean,
    colors: ReaderColors,
    onClick: () -> Unit
) {
    val isPart = item.level == 0 && (item.title.startsWith("Part", ignoreCase = true) || item.anchor == null)
    val indent = when (item.level) {
        0 -> 0.dp
        1 -> 12.dp
        2 -> 24.dp
        else -> 36.dp
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent),
        shape = RoundedCornerShape(8.dp),
        color = if (isPart) {
            colors.accent.copy(alpha = 0.08f)
        } else if (isCurrentChapter && item.level <= 1) {
            colors.accent.copy(alpha = 0.09f)
        } else {
            Color.Transparent
        },
        contentColor = colors.foreground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = if (isPart) 9.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (item.level) {
                0 -> {
                    if (isPart) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.accent)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                1 -> {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(if (isCurrentChapter) colors.accent else colors.muted.copy(alpha = 0.6f))
                    )
                    Spacer(Modifier.width(8.dp))
                }
                2 -> {
                    Text(
                        text = "•",
                        fontSize = 11.sp,
                        color = colors.muted.copy(alpha = 0.6f),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                else -> {
                    Text(
                        text = "◦",
                        fontSize = 10.sp,
                        color = colors.muted.copy(alpha = 0.45f),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            Text(
                text = item.title,
                fontSize = when (item.level) {
                    0 -> 13.5.sp
                    1 -> 13.sp
                    2 -> 12.sp
                    else -> 11.5.sp
                },
                fontWeight = when {
                    isPart -> FontWeight.Bold
                    item.level == 1 -> FontWeight.SemiBold
                    isCurrentChapter -> FontWeight.Medium
                    else -> FontWeight.Normal
                },
                color = when {
                    isPart -> colors.accent
                    isCurrentChapter -> colors.accent
                    item.level >= 3 -> colors.muted
                    else -> colors.foreground
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            if (item.level <= 1 && isCurrentChapter) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Capítulo actual",
                    tint = colors.accent,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

@Composable
private fun TocHighlightRow(
    highlight: HighlightEntity,
    chapterTitle: String,
    colors: ReaderColors,
    onClick: () -> Unit
) {
    val dotColor = when (HighlightColor.fromKey(highlight.color)) {
        HighlightColor.YELLOW -> Color(0xFFF2C14E)
        HighlightColor.GREEN -> Color(0xFF7BC47F)
        HighlightColor.BLUE -> Color(0xFF6FA8DC)
        HighlightColor.PINK -> Color(0xFFE89AB8)
        HighlightColor.PURPLE -> Color(0xFFC0A0E8)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.06f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = chapterTitle,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = highlight.selectedText,
                    fontSize = 12.sp,
                    color = colors.foreground,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                if (!highlight.note.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "💬 ${highlight.note}",
                        fontSize = 11.sp,
                        color = colors.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun TocChapterRow(
    number: String,
    title: String,
    subtitle: String,
    wordCount: Int,
    selected: Boolean,
    colors: ReaderColors,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.accent.copy(alpha = 0.11f) else Color.Transparent,
        contentColor = colors.foreground
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = number,
                modifier = Modifier.width(30.dp),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.accent else colors.muted
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) colors.accent else colors.foreground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (title != subtitle) {
                    Text(
                        text = subtitle,
                        fontSize = 10.sp,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Capítulo actual",
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = "$wordCount palabras",
                fontSize = 9.5.sp,
                color = colors.muted,
                maxLines = 1
            )
        }
    }
}

@Composable
fun QuickActionSheet(
    action: QuickActionUi,
    colors: ReaderColors,
    onDismiss: () -> Unit,
    onAskBook: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.foreground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.foreground.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (action.type == QuickActionType.DICTIONARY) {
                            Icons.Outlined.Translate
                        } else {
                            Icons.Outlined.AutoAwesome
                        },
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = action.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = action.subtitle,
                        fontSize = 11.sp,
                        color = colors.muted
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Cerrar",
                        tint = colors.muted
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            if (action.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.foreground.copy(alpha = 0.08f)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Consultando el contexto del capítulo…",
                    fontSize = 12.sp,
                    color = colors.muted
                )
            }
            if (action.content.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    MarkdownText(
                        markdown = action.content,
                        color = colors.foreground,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp
                    )
                }
            }
            if (action.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = action.error,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAskBook,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Preguntar al libro", fontSize = 12.sp)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cerrar", fontSize = 12.sp, color = colors.muted)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun FloatingAiPane(
    state: ReaderUiState,
    colors: ReaderColors,
    onModeChange: (AiPaneMode) -> Unit,
    onTabChange: (AiTab) -> Unit,
    onOpenSettings: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onInputChange: (String) -> Unit,
    onClearChat: () -> Unit,
    onAskSuggestion: (String) -> Unit,
    onOpenSource: (SourceRef) -> Unit,
    onJumpToHighlight: (HighlightEntity) -> Unit,
    onDeleteHighlight: (HighlightEntity) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val paneWidth = (maxWidth * 0.38f).coerceIn(360.dp, 460.dp)
            .coerceAtMost((maxWidth - 24.dp).coerceAtLeast(260.dp))
        val paneHeight = (maxHeight * 0.74f).coerceAtMost(640.dp)
            .coerceAtLeast((maxHeight - 24.dp).coerceAtMost(360.dp))
        val maxX = with(density) { (maxWidth - paneWidth).toPx() }.coerceAtLeast(0f)
        val maxY = with(density) { (maxHeight - paneHeight).toPx() }.coerceAtLeast(0f)
        val margin = with(density) { 16.dp.toPx() }
        var offsetX by remember(maxWidth, paneWidth) {
            mutableStateOf((maxX - margin).coerceAtLeast(0f))
        }
        var offsetY by remember(maxHeight, paneHeight) {
            mutableStateOf((maxY - margin).coerceAtLeast(0f))
        }
        val dragModifier = Modifier.pointerInput(maxX, maxY) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                offsetX = (offsetX + dragAmount.x).coerceIn(0f, maxX)
                offsetY = (offsetY + dragAmount.y).coerceIn(0f, maxY)
            }
        }
        AiAssistantPane(
            state = state,
            colors = colors,
            floating = true,
            dragHandleModifier = dragModifier,
            onModeChange = onModeChange,
            onTabChange = onTabChange,
            onClose = { onModeChange(AiPaneMode.MINIMIZED) },
            onOpenSettings = onOpenSettings,
            onSend = onSend,
            onStop = onStop,
            onInputChange = onInputChange,
            onClearChat = onClearChat,
            onAskSuggestion = onAskSuggestion,
            onOpenSource = onOpenSource,
            onJumpToHighlight = onJumpToHighlight,
            onDeleteHighlight = onDeleteHighlight,
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(paneWidth)
                .height(paneHeight)
                .zIndex(20f)
        )
    }
}

@Composable
fun MinimizedAiBubble(
    streaming: Boolean,
    colors: ReaderColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = colors.accent,
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = "Abrir asistente",
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "IA",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            if (streaming) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
fun HiddenTopBarButton(
    bookTitle: String?,
    colors: ReaderColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(12.dp)
            .shadow(6.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = colors.surface.copy(alpha = 0.94f),
        contentColor = colors.foreground,
        border = BorderStroke(1.dp, colors.foreground.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = "Mostrar barra superior",
                tint = colors.accent,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = bookTitle ?: "Leer",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
fun HighlightActionSheet(
    highlight: HighlightEntity,
    colors: ReaderColors,
    onDismiss: () -> Unit,
    onAsk: () -> Unit,
    onDefine: () -> Unit,
    onExplain: () -> Unit,
    onRemove: () -> Unit
) {
    val dotColor = when (HighlightColor.fromKey(highlight.color)) {
        HighlightColor.YELLOW -> Color(0xFFF2C14E)
        HighlightColor.GREEN -> Color(0xFF7BC47F)
        HighlightColor.BLUE -> Color(0xFF6FA8DC)
        HighlightColor.PINK -> Color(0xFFE89AB8)
        HighlightColor.PURPLE -> Color(0xFFC0A0E8)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        contentColor = colors.foreground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.foreground.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Subrayado · ${highlight.pageLabel}",
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Cerrar",
                        tint = colors.muted
                    )
                }
            }
            Text(
                text = "«${highlight.selectedText}»",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = colors.foreground,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onAsk,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Outlined.ChatBubbleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Preguntar", fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = onDefine,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Outlined.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Definir", fontSize = 11.5.sp)
                }
                OutlinedButton(
                    onClick = onExplain,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Explicar", fontSize = 11.5.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Quitar subrayado",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}


@Composable
fun ReferenceSheet(
    references: List<ReaderReference>,
    colors: ReaderColors,
    onDismiss: () -> Unit,
    onJumpToSource: ((ReaderReference) -> Unit)? = null
) {
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(references) {
        Log.d("LectorReader", "ReferenceSheet composed refs=${references.size}")
    }
    ModalBottomSheet(
        onDismissRequest = {
            Log.d("LectorReader", "ReferenceSheet dismissed")
            onDismiss()
        },
        containerColor = colors.surface,
        contentColor = colors.foreground,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.foreground.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = null,
                    tint = colors.accent
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (references.size == 1) "Referencia" else "Referencias",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground
                    )
                    Text(
                        text = "Vista rápida sin salir de la página",
                        fontSize = 10.5.sp,
                        color = colors.muted
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Cerrar",
                        tint = colors.muted
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = colors.foreground.copy(alpha = 0.08f))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(references, key = { it.referenceKey }) { reference ->
                    val expanded = reference.referenceKey in expandedKeys
                    val isLong = reference.content.length > 700
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = colors.background.copy(alpha = 0.45f),
                        border = BorderStroke(
                            1.dp,
                            colors.foreground.copy(alpha = 0.08f)
                        )
                    ) {
                        Column(Modifier.padding(13.dp)) {
                            val (pillText, subtitleText) = when {
                                reference.label.startsWith("Afiliación", ignoreCase = true) ->
                                    reference.label to "Afiliación"
                                reference.label.startsWith("Contacto", ignoreCase = true) ->
                                    "Contacto" to "Información de autor"
                                reference.label.startsWith("Nota", ignoreCase = true) ->
                                    reference.label to "Nota al pie"
                                reference.anchor?.startsWith("fn", ignoreCase = true) == true ||
                                    reference.referenceKey.contains(":fn", ignoreCase = true) ->
                                    "Nota ${reference.numericKey ?: reference.label}" to "Nota al pie"
                                reference.referenceKey.startsWith("cr", ignoreCase = true) ||
                                    reference.anchor?.startsWith("cr", ignoreCase = true) == true ->
                                    "Cita [${reference.numericKey ?: reference.label}]" to "Bibliografía"
                                else -> reference.label to "Referencia"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = colors.accent.copy(alpha = 0.14f),
                                    contentColor = colors.accent
                                ) {
                                    Text(
                                        text = pillText,
                                        modifier = Modifier.padding(
                                            horizontal = 7.dp,
                                            vertical = 3.dp
                                        ),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = subtitleText,
                                    fontSize = 10.sp,
                                    color = colors.muted
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = if (isLong && !expanded) {
                                    reference.content.take(700).trimEnd() + "…"
                                } else {
                                    reference.content
                                },
                                fontSize = 12.5.sp,
                                lineHeight = 19.sp,
                                color = colors.foreground
                            )
                            if (isLong) {
                                TextButton(
                                    onClick = {
                                        expandedKeys = if (expanded) {
                                            expandedKeys - reference.referenceKey
                                        } else {
                                            expandedKeys + reference.referenceKey
                                        }
                                    }
                                ) {
                                    Text(
                                        text = if (expanded) "Ver menos" else "Mostrar todo",
                                        fontSize = 11.sp,
                                        color = colors.accent
                                    )
                                }
                            }
                            if (onJumpToSource != null && reference.chapterIndex >= 0) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    TextButton(
                                        onClick = { onJumpToSource(reference) }
                                    ) {
                                        Icon(
                                            Icons.Outlined.OpenInNew,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Ir a la ubicación en el libro",
                                            fontSize = 11.sp,
                                            color = colors.accent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

private sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState
    data object Failed : ImagePreviewState
    data class Ready(val bitmap: Bitmap) : ImagePreviewState
}

/**
 * Visor de imágenes del libro a pantalla completa.
 *
 * - Carga el bitmap con BitmapFactory en Dispatchers.IO (produceState) y lo
 *   ajusta al alto/ancho disponible con ContentScale.Fit.
 * - Pinch-to-zoom de 1x a 6x con detectTransformGestures. El zoom y el pan se
 *   aplican con Modifier.graphicsLayer leyendo el estado en la fase de
 *   dibujo, por lo que el gesto no desencadena recomposición y se mantiene
 *   fluido. El pan queda acotado al marco ampliado de la figura para que la
 *   imagen no se pierda fuera de la pantalla.
 * - Doble toque reinicia el zoom; un toque fuera de la figura, el botón ✕ y
 *   el gesto de atrás del sistema cierran el visor.
 */
@Composable
fun ImagePreviewDialog(
    imagePath: String,
    colors: ReaderColors,
    onDismiss: () -> Unit
) {
    val imageState by produceState<ImagePreviewState>(
        initialValue = ImagePreviewState.Loading,
        key1 = imagePath
    ) {
        val bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
        }
        value = if (bitmap != null) {
            ImagePreviewState.Ready(bitmap)
        } else {
            ImagePreviewState.Failed
        }
    }
    var scale by remember(imagePath) { mutableStateOf(1f) }
    var offset by remember(imagePath) { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { tap ->
                            val bitmap = (imageState as? ImagePreviewState.Ready)?.bitmap
                            if (bitmap == null) {
                                onDismiss()
                            } else {
                                // Rect ajustado (ContentScale.Fit) y luego
                                // transformado por el zoom alrededor del centro
                                // más el pan, exactamente igual que el
                                // graphicsLayer de la imagen: fuera de él, el
                                // toque cierra el visor.
                                val boxWidth = size.width.toFloat()
                                val boxHeight = size.height.toFloat()
                                val aspect = bitmap.width.toFloat() /
                                    bitmap.height.toFloat().coerceAtLeast(1f)
                                var fittedWidth = boxWidth
                                var fittedHeight = boxWidth / aspect
                                if (fittedHeight > boxHeight) {
                                    fittedHeight = boxHeight
                                    fittedWidth = boxHeight * aspect
                                }
                                val margin = 16.dp.toPx()
                                val halfWidth = fittedWidth * scale / 2f
                                val halfHeight = fittedHeight * scale / 2f
                                val centerX = boxWidth / 2f + offset.x
                                val centerY = boxHeight / 2f + offset.y
                                val insideImage = tap.x >= centerX - halfWidth - margin &&
                                    tap.x <= centerX + halfWidth + margin &&
                                    tap.y >= centerY - halfHeight - margin &&
                                    tap.y <= centerY + halfHeight + margin
                                if (!insideImage) onDismiss()
                            }
                        },
                        onDoubleTap = {
                            scale = 1f
                            offset = Offset.Zero
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 6f)
                        scale = newScale
                        if (newScale <= 1f) {
                            // Sin zoom no hay pan: la figura vuelve a quedar
                            // centrada en lugar de quedar desplazada al 100%.
                            offset = Offset.Zero
                        } else {
                            // Acota el pan al marco ampliado de la imagen para
                            // que la figura nunca salga del todo de la pantalla.
                            val bitmap = (imageState as? ImagePreviewState.Ready)?.bitmap
                            val boxWidth = size.width.toFloat()
                            val boxHeight = size.height.toFloat()
                            var fittedWidth = boxWidth
                            var fittedHeight = boxHeight
                            if (bitmap != null && bitmap.height > 0) {
                                val aspect = bitmap.width.toFloat() / bitmap.height
                                fittedWidth = boxWidth
                                fittedHeight = boxWidth / aspect
                                if (fittedHeight > boxHeight) {
                                    fittedHeight = boxHeight
                                    fittedWidth = boxHeight * aspect
                                }
                            }
                            val maxX = ((fittedWidth * newScale - boxWidth) / 2f)
                                .coerceAtLeast(0f)
                            val maxY = ((fittedHeight * newScale - boxHeight) / 2f)
                                .coerceAtLeast(0f)
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY)
                            )
                        }
                    }
                }
        ) {
            when (val state = imageState) {
                ImagePreviewState.Loading -> {
                    CircularProgressIndicator(
                        color = colors.accent,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(34.dp)
                    )
                }
                ImagePreviewState.Failed -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Outlined.BrokenImage,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.size(34.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "No se pudo cargar la imagen",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = onDismiss) {
                            Text("Cerrar", fontSize = 12.sp, color = colors.accent)
                        }
                    }
                }
                is ImagePreviewState.Ready -> {
                    val imageBitmap = remember(state.bitmap) {
                        state.bitmap.asImageBitmap()
                    }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Imagen del libro",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            // Leer el estado dentro de la lambda hace que el
                            // gesto solo redibuje la capa, sin recomponer.
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                }
            }

            // Botón de cierre encima de todo, sin verse afectado por el zoom.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 12.dp, end = 16.dp)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Cerrar imagen",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (scale > 1.01f) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 18.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White,
                    shadowElevation = 4.dp
                ) {
                    Text(
                        text = "${(scale * 100).roundToInt()}%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}


/**
 * Número visible del capítulo en el TOC. Usa el número que abre el título
 * ("11. Advanced Reasoning" -> "11") en vez del índice de spine, que incluye
 * portada, copyright, prefacio, etc. y confunde al lector.
 */
private fun chapterDisplayNumber(chapter: ChapterSummary): String {
    val match = Regex("^\\s*(\\d+(?:\\.\\d+)*)").find(chapter.title)
    if (match != null) return match.groupValues[1]
    return chapter.pageLabel
        .removePrefix("Capítulo ")
        .removePrefix("Página ")
        .ifBlank { (chapter.chapterIndex + 1).toString() }
}
