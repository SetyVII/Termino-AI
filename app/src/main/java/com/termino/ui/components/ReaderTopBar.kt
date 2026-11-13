package com.termino.ui.components

import com.termino.R
import com.termino.core.reader.ReaderColors
import androidx.compose.ui.res.stringResource
import com.termino.core.reader.ReaderFont
import com.termino.core.reader.ReaderTheme
import com.termino.core.reader.readerColors
import androidx.compose.animation.togetherWith

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termino.data.model.AiPaneMode
import com.termino.ui.reader.ReaderUiState

@Composable
fun ReaderTopBar(
    state: ReaderUiState,
    colors: ReaderColors,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onFont: (ReaderFont) -> Unit,
    onEyeCare: (Boolean) -> Unit,
    onToggleAi: () -> Unit,
    onSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    onTogglePdfMode: (() -> Unit)?,
    onOpenAppearance: () -> Unit,
    typefaceFor: (ReaderFont) -> android.graphics.Typeface,
    onImportFont: (android.net.Uri) -> Unit,
    onDeleteFont: (ReaderFont) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val iconButtonSize = if (compact) 40.dp else 48.dp
    val iconSize = if (compact) 21.dp else 24.dp
    val barHeight = if (compact) 52.dp else 58.dp
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colors.surface.copy(alpha = 0.97f),
        contentColor = colors.foreground,
        shadowElevation = 5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(barHeight)
                .padding(horizontal = if (compact) 2.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(iconButtonSize)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.back_to_library),
                    tint = colors.foreground,
                    modifier = Modifier.size(iconSize)
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 2.dp, end = 4.dp)
            ) {
                Text(
                    text = state.book?.title ?: "Término",
                    fontSize = if (compact) 13.sp else 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = state.currentChapter?.title
                        ?: state.currentChapter?.pageLabel
                        ?: stringResource(R.string.loading),
                    fontSize = if (compact) 10.sp else 10.5.sp,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!compact) {
                IconButton(onClick = onSearch, modifier = Modifier.size(iconButtonSize)) {
                    Icon(
                        androidx.compose.material.icons.Icons.Outlined.Search,
                        contentDescription = stringResource(R.string.search_in_book),
                        tint = colors.foreground,
                        modifier = Modifier.size(iconSize)
                    )
                }
                if (onTogglePdfMode != null) {
                    PdfModeButton(state, colors, onTogglePdfMode, iconButtonSize, iconSize)
                }
            }
            BookmarkButton(
                bookmarked = state.currentBookmark != null,
                colors = colors,
                onClick = onToggleBookmark,
                buttonSize = iconButtonSize,
                iconSize = iconSize
            )
            IconButton(
                onClick = onOpenToc,
                modifier = Modifier.size(iconButtonSize)
            ) {
                Icon(
                    Icons.Outlined.MenuBook,
                    contentDescription = stringResource(R.string.contents),
                    tint = colors.foreground,
                    modifier = Modifier.size(iconSize)
                )
            }
            IconButton(
                onClick = onToggleAi,
                modifier = Modifier.size(iconButtonSize)
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = if (state.paneMode == AiPaneMode.MINIMIZED) {
                        stringResource(R.string.assistant_show)
                    } else {
                        stringResource(R.string.assistant_hide)
                    },
                    tint = if (state.paneMode == AiPaneMode.MINIMIZED) {
                        colors.foreground
                    } else {
                        colors.accent
                    },
                    modifier = Modifier.size(iconSize)
                )
            }
            if (!compact) {
                ThemeMenu(
                    state = state,
                    colors = colors,
                    onTheme = onTheme,
                    onEyeCare = onEyeCare,
                    buttonSize = iconButtonSize,
                    iconSize = iconSize
                )
                TypeMenu(
                    state = state,
                    colors = colors,
                    onFontSize = onFontSize,
                    onLineHeight = onLineHeight,
                    onFont = onFont,
                    typefaceFor = typefaceFor,
                    onImportFont = onImportFont,
                    onDeleteFont = onDeleteFont,
                    buttonSize = iconButtonSize,
                    iconSize = iconSize
                )
            }
            if (compact) {
                CompactOverflow(
                    state = state,
                    colors = colors,
                    onSearch = onSearch,
                    onTogglePdfMode = onTogglePdfMode,
                    onOpenAppearance = onOpenAppearance,
                    buttonSize = iconButtonSize,
                    iconSize = iconSize
                )
            }
        }
    }
}

@Composable
private fun ThemeMenu(
    state: ReaderUiState,
    colors: ReaderColors,
    onTheme: (ReaderTheme) -> Unit,
    onEyeCare: (Boolean) -> Unit,
    buttonSize: Dp,
    iconSize: Dp
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                Icons.Outlined.Palette,
                contentDescription = stringResource(R.string.reading_theme),
                tint = colors.foreground,
                modifier = Modifier.size(iconSize)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface
        ) {
            ReaderTheme.entries.forEach { theme ->
                val themeColors = readerColors(theme)
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemeSwatch(
                                theme = theme,
                                size = 20.dp,
                                modifier = Modifier.border(
                                    1.dp,
                                    themeColors.foreground.copy(alpha = 0.25f),
                                    CircleShape
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = stringResource(theme.labelRes),
                                fontSize = 13.sp,
                                color = colors.foreground
                            )
                        }
                    },
                    onClick = {
                        onTheme(theme)
                        expanded = false
                    },
                    trailingIcon = {
                        if (state.themeChoice == theme) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = null,
                                tint = colors.accent
                            )
                        }
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = colors.foreground,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.eye_care),
                            fontSize = 13.sp,
                            color = colors.foreground
                        )
                    }
                },
                onClick = {
                    onEyeCare(!state.eyeCare)
                    expanded = false
                },
                trailingIcon = {
                    if (state.eyeCare) {
                        Icon(
                            Icons.Outlined.Check,
                            contentDescription = null,
                            tint = colors.accent
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun TypeMenu(
    state: ReaderUiState,
    colors: ReaderColors,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onFont: (ReaderFont) -> Unit,
    typefaceFor: (ReaderFont) -> android.graphics.Typeface,
    onImportFont: (android.net.Uri) -> Unit,
    onDeleteFont: (ReaderFont) -> Unit,
    buttonSize: Dp,
    iconSize: Dp
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                Icons.Outlined.FormatSize,
                contentDescription = stringResource(R.string.typography),
                tint = colors.foreground,
                modifier = Modifier.size(iconSize)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface,
            modifier = Modifier.widthIn(max = 340.dp).width(340.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = stringResource(R.string.typography),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.font_size),
                        modifier = Modifier.weight(1f),
                        fontSize = 12.sp,
                        color = colors.muted
                    )
                    IconButton(
                        onClick = { onFontSize(state.fontSize - 1) },
                        enabled = state.fontSize > com.termino.core.reader.MIN_FONT_SIZE,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Remove,
                            contentDescription = stringResource(R.string.font_decrease),
                            tint = colors.foreground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = "${state.fontSize}",
                        modifier = Modifier.width(28.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.foreground
                    )
                    IconButton(
                        onClick = { onFontSize(state.fontSize + 1) },
                        enabled = state.fontSize < com.termino.core.reader.MAX_FONT_SIZE,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.font_increase),
                            tint = colors.foreground,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.line_height),
                    fontSize = 12.sp,
                    color = colors.muted
                )
                Slider(
                    value = state.lineHeight,
                    onValueChange = onLineHeight,
                    valueRange = 1.2f..2.2f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.foreground.copy(alpha = 0.15f)
                    )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.font),
                    fontSize = 12.sp,
                    color = colors.muted
                )
                Spacer(Modifier.height(6.dp))
                FontPickerRow(
                    fonts = state.fonts,
                    selected = state.font,
                    accent = colors.accent,
                    foreground = colors.foreground,
                    muted = colors.muted,
                    typefaceFor = typefaceFor,
                    onSelect = onFont,
                    onImport = onImportFont,
                    onDelete = onDeleteFont
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** Page bookmark button with a fill transition (scale + fade). */
@Composable
private fun BookmarkButton(
    bookmarked: Boolean,
    colors: ReaderColors,
    onClick: () -> Unit,
    buttonSize: Dp,
    iconSize: Dp
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            onClick()
        },
        modifier = Modifier.size(buttonSize)
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = bookmarked,
            transitionSpec = {
                (androidx.compose.animation.scaleIn(
                    androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f),
                    initialScale = 0.4f
                ) + androidx.compose.animation.fadeIn()) togetherWith androidx.compose.animation.fadeOut()
            },
            label = "bookmark"
        ) { marked ->
            Icon(
                if (marked) androidx.compose.material.icons.Icons.Outlined.Bookmark
                else androidx.compose.material.icons.Icons.Outlined.BookmarkBorder,
                contentDescription = if (marked) stringResource(R.string.bookmark_remove) else stringResource(R.string.bookmark_add),
                tint = if (marked) colors.accent else colors.foreground,
                modifier = Modifier.size(iconSize)
            )
        }
    }
}

@Composable
private fun PdfModeButton(
    state: ReaderUiState,
    colors: ReaderColors,
    onToggle: () -> Unit,
    buttonSize: Dp,
    iconSize: Dp
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(buttonSize)) {
        Icon(
            if (state.pdfOriginalPages) androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Article
            else androidx.compose.material.icons.Icons.Outlined.PictureAsPdf,
            contentDescription = if (state.pdfOriginalPages) stringResource(R.string.pdf_view_text) else stringResource(R.string.pdf_view_original),
            tint = colors.foreground,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** Phone overflow menu: search, appearance and PDF mode fit without crowding the bar. */
@Composable
private fun CompactOverflow(
    state: ReaderUiState,
    colors: ReaderColors,
    onSearch: () -> Unit,
    onTogglePdfMode: (() -> Unit)?,
    onOpenAppearance: () -> Unit,
    buttonSize: Dp,
    iconSize: Dp
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(buttonSize)) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = colors.foreground,
                modifier = Modifier.size(iconSize)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = colors.surface
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_in_book), color = colors.foreground) },
                leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Search, null, tint = colors.foreground) },
                onClick = { expanded = false; onSearch() }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.appearance_typography), color = colors.foreground) },
                leadingIcon = { Icon(Icons.Outlined.FormatSize, null, tint = colors.foreground) },
                onClick = { expanded = false; onOpenAppearance() }
            )
            if (onTogglePdfMode != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (state.pdfOriginalPages) stringResource(R.string.pdf_view_text) else stringResource(R.string.pdf_view_original),
                            color = colors.foreground
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (state.pdfOriginalPages) androidx.compose.material.icons.Icons.AutoMirrored.Outlined.Article
                            else androidx.compose.material.icons.Icons.Outlined.PictureAsPdf,
                            null,
                            tint = colors.foreground
                        )
                    },
                    onClick = { expanded = false; onTogglePdfMode() }
                )
            }
        }
    }
}
