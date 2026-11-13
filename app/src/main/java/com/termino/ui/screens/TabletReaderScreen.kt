@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.termino.ui.screens

import com.termino.R
import com.termino.core.reader.NativeReaderView
import androidx.compose.ui.res.stringResource
import com.termino.core.reader.readerColors
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termino.data.model.AiPaneMode
import com.termino.ui.components.AiAssistantPane
import com.termino.ui.components.FloatingAiPane
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.runtime.setValue
import com.termino.ui.components.HighlightActionSheet
import com.termino.ui.components.ImagePreviewDialog
import com.termino.ui.components.MinimizedAiBubble
import com.termino.ui.components.QuickActionSheet
import com.termino.ui.components.ReferenceSheet
import com.termino.ui.components.ReaderPageBar
import com.termino.ui.components.ReaderProgressLine
import com.termino.ui.components.ReaderTocSheet
import com.termino.ui.components.ReaderTopBar
import com.termino.ui.reader.ReaderUiState
import com.termino.ui.reader.ReaderViewModel

@Composable
fun TabletReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val content by viewModel.content.collectAsStateWithLifecycle()
    val colors = readerColors(state.theme)
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showAppearance by remember { androidx.compose.runtime.mutableStateOf(false) }
    ReaderWindowEffects(state = state, viewModel = viewModel)
    // The reader "Auto" theme follows the effective app light/dark mode.
    val appDark = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(appDark) { viewModel.setAppDark(appDark) }
    val typefaceFor = rememberTypefaceResolver()

    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            message = message,
            withDismissAction = true,
            duration = SnackbarDuration.Short
        )
        viewModel.clearToast()
    }

    LaunchedEffect(state.externalLink) {
        val url = state.externalLink ?: return@LaunchedEffect
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        viewModel.consumeExternalLink()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        val isTablet = maxWidth >= 800.dp
        val compact = maxWidth < 600.dp
        val docked = isTablet &&
            state.paneMode == AiPaneMode.DOCKED &&
            state.paneFraction > 0f
        val paneWidth = (maxWidth * state.paneFraction).coerceIn(280.dp, 520.dp)
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }

        Row(Modifier.fillMaxSize()) {
            ReaderPane(
                state = state,
                content = content,
                viewModel = viewModel,
                colors = colors,
                onBack = onBack,
                onOpenSettings = onOpenSettings,
                compact = compact,
                onOpenAppearance = { showAppearance = true },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            if (docked) {
                Box(
                    modifier = Modifier
                        .width(9.dp)
                        .fillMaxHeight()
                        .pointerInput(widthPx) {
                            detectHorizontalDragGestures(
                                onDragEnd = { viewModel.onPaneDragEnd() },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    val current = viewModel.ui.value.paneFraction
                                    viewModel.setPaneFraction(
                                        current - dragAmount / widthPx.coerceAtLeast(1f)
                                    )
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.foreground.copy(alpha = 0.14f))
                    )
                }
                Box(
                    modifier = Modifier
                        .width(paneWidth)
                        .fillMaxHeight()
                        .background(colors.surface)
                ) {
                    AiAssistantPane(
                        state = state,
                        colors = colors,
                        floating = false,
                        onModeChange = viewModel::setPaneMode,
                        onTabChange = viewModel::setAiTab,
                        onClose = { viewModel.setPaneMode(AiPaneMode.MINIMIZED) },
                        onOpenSettings = onOpenSettings,
                        onSend = { viewModel.sendMessage() },
                        onStop = viewModel::stopStreaming,
                        onInputChange = viewModel::setChatInput,
                        onClearChat = viewModel::clearChat,
                        onAskSuggestion = { viewModel.sendMessage(it) },
                        onOpenSource = { source -> viewModel.jumpToChapter(source.chapterIndex) },
                        onJumpToHighlight = viewModel::jumpToHighlight,
                        onDeleteHighlight = { highlight -> viewModel.removeHighlight(highlight.id) },
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                    )
                }
            }
        }

        if (!isTablet && state.paneMode == AiPaneMode.DOCKED) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
                color = colors.surface,
                contentColor = colors.foreground
            ) {
                AiAssistantPane(
                    state = state,
                    colors = colors,
                    floating = false,
                    onModeChange = viewModel::setPaneMode,
                    onTabChange = viewModel::setAiTab,
                    onClose = { viewModel.setPaneMode(AiPaneMode.MINIMIZED) },
                    onOpenSettings = onOpenSettings,
                    onSend = { viewModel.sendMessage() },
                    onStop = viewModel::stopStreaming,
                    onInputChange = viewModel::setChatInput,
                    onClearChat = viewModel::clearChat,
                    onAskSuggestion = { viewModel.sendMessage(it) },
                    onOpenSource = { source -> viewModel.jumpToChapter(source.chapterIndex) },
                    onJumpToHighlight = viewModel::jumpToHighlight,
                    onDeleteHighlight = { highlight -> viewModel.removeHighlight(highlight.id) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (state.paneMode == AiPaneMode.FLOATING) {
            FloatingAiPane(
                state = state,
                colors = colors,
                onModeChange = viewModel::setPaneMode,
                onTabChange = viewModel::setAiTab,
                onOpenSettings = onOpenSettings,
                onSend = { viewModel.sendMessage() },
                onStop = viewModel::stopStreaming,
                onInputChange = viewModel::setChatInput,
                onClearChat = viewModel::clearChat,
                onAskSuggestion = { viewModel.sendMessage(it) },
                onOpenSource = { source -> viewModel.jumpToChapter(source.chapterIndex) },
                onJumpToHighlight = viewModel::jumpToHighlight,
                onDeleteHighlight = { highlight -> viewModel.removeHighlight(highlight.id) }
            )
        }

        if (state.paneMode == AiPaneMode.MINIMIZED) {
            MinimizedAiBubble(
                streaming = state.isStreaming,
                colors = colors,
                onClick = { viewModel.setPaneMode(AiPaneMode.DOCKED) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    // On phones the page bar takes the bottom edge: the bubble is raised
                    // so it does not cover it or sit under the snackbar.
                    .padding(end = if (compact) 14.dp else 20.dp, bottom = if (compact) 78.dp else 20.dp)
            )
        }

        // Eye-care filter: a warm veil over the whole reader (text, top bar
        // and AI panel) to reduce blue light. It only paints a background;
        // with no clickable or pointerInput it consumes no touches, so
        // gestures reach the content below. Sheets and dialogs are separate
        // windows and stay above this filter.
        if (state.eyeCare) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x16FF9800)) // very soft amber, ~9% alpha
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 72.dp)
        )
    }

    if (state.showToc) {
        ReaderTocSheet(
            state = state,
            colors = colors,
            onDismiss = { viewModel.setShowToc(false) },
            onSelectChapter = viewModel::jumpToChapter,
            onSelectTocItem = viewModel::onSelectTocItem,
            onSelectHighlight = viewModel::jumpToHighlight,
            onSelectBookmark = viewModel::openBookmark,
            onDeleteBookmark = { viewModel.deleteBookmark(it.id) }
        )
    }

    state.search?.let { search ->
        com.termino.ui.components.ReaderSearchSheet(
            search = search,
            colors = colors,
            onQueryChange = viewModel::search,
            onOpenHit = viewModel::openSearchHit,
            onDismiss = viewModel::closeSearch
        )
    }

    if (showAppearance) {
        com.termino.ui.components.ReaderAppearanceSheet(
            state = state,
            colors = colors,
            onTheme = viewModel::setTheme,
            onEyeCare = viewModel::setEyeCare,
            onFontSize = viewModel::setFontSize,
            onLineHeight = viewModel::setLineHeight,
            onFont = viewModel::setFont,
            typefaceFor = typefaceFor,
            onImportFont = viewModel::importFont,
            onDeleteFont = viewModel::deleteFont,
            onDismiss = { showAppearance = false }
        )
    }

    state.quickAction?.let { action ->
        QuickActionSheet(
            action = action,
            colors = colors,
            onDismiss = viewModel::dismissQuickAction,
            onAskBook = viewModel::askFromQuickAction
        )
    }

    state.highlightAction?.let { highlight ->
        HighlightActionSheet(
            highlight = highlight,
            colors = colors,
            onDismiss = viewModel::dismissHighlightAction,
            onAsk = viewModel::askHighlight,
            onDefine = viewModel::defineHighlight,
            onExplain = viewModel::explainHighlight,
            onRemove = { viewModel.removeHighlight(highlight.id) }
        )
    }

    state.referencePreview?.let { references ->
        ReferenceSheet(
            references = references,
            colors = colors,
            onDismiss = viewModel::dismissReferencePreview,
            onJumpToSource = viewModel::jumpToReferenceSource
        )
    }

    state.imagePreviewPath?.let { path ->
        ImagePreviewDialog(
            imagePath = path,
            colors = colors,
            onDismiss = viewModel::dismissImagePreview
        )
    }
}

@Composable
private fun ReaderPane(
    state: ReaderUiState,
    content: com.termino.core.reader.ReaderContent?,
    viewModel: ReaderViewModel,
    colors: com.termino.core.reader.ReaderColors,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    compact: Boolean,
    onOpenAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(enabled = state.canNavigateBack) {
        viewModel.navigateBack()
    }

    // The bars overlay the text: showing or hiding them does not change the
    // reading area, so the text neither jumps nor repaginates.
    Box(
        modifier = modifier
            .background(colors.background)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // Insets "ignoring visibility": the area does not change in immersive mode.
                .windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.statusBarsIgnoringVisibility
                )
                .windowInsetsPadding(
                    androidx.compose.foundation.layout.WindowInsets.navigationBarsIgnoringVisibility
                )
        ) {
            val book = state.book
            androidx.compose.animation.Crossfade(
                targetState = state.showPdfPages && book != null,
                animationSpec = tween(260),
                label = "reader-mode"
            ) { pdfPages ->
                if (pdfPages && book != null) {
                    com.termino.core.reader.PdfPageReader(
                        filePath = book.filePath,
                        currentPage = state.currentChapterIndex,
                        colors = colors,
                        theme = state.theme,
                        tapZones = state.tapZones,
                        onPageSettled = viewModel::onPdfPageSettled,
                        onCenterTap = viewModel::toggleTopBar,
                        onUserPageTurn = viewModel::onUserPageTurn,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    NativeReaderView(
                        controller = viewModel.controller,
                        content = content,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (state.loading && state.error == null) {
                LoadingOverlay(colors = colors)
            }
            // Font size indicator while pinching.
            AnimatedVisibility(
                visible = state.fontSizePreview != null,
                modifier = Modifier.align(Alignment.Center),
                enter = fadeIn(tween(120)) + androidx.compose.animation.scaleIn(initialScale = 0.85f),
                exit = fadeOut(tween(180))
            ) {
                var shown by remember { androidx.compose.runtime.mutableIntStateOf(state.fontSize) }
                state.fontSizePreview?.let { shown = it }
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surface.copy(alpha = 0.96f),
                    contentColor = colors.foreground,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Aa", fontSize = 15.sp, color = colors.accent, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(10.dp))
                        androidx.compose.animation.AnimatedContent(
                            targetState = shown,
                            transitionSpec = {
                                val up = targetState > initialState
                                (androidx.compose.animation.slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                                    (androidx.compose.animation.slideOutVertically { if (up) -it else it } + fadeOut())
                            },
                            label = "font-size"
                        ) { size ->
                            Text("$size", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
            if (state.error != null) {
                ReaderErrorOverlay(
                    message = state.error,
                    colors = colors,
                    onBack = onBack
                )
            }
            if (state.canNavigateBack) {
                Surface(
                    onClick = viewModel::navigateBack,
                    shape = RoundedCornerShape(20.dp),
                    color = colors.surface.copy(alpha = 0.95f),
                    contentColor = colors.accent,
                    border = BorderStroke(1.dp, colors.accent.copy(alpha = 0.35f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 16.dp, top = if (state.showTopBar) (if (compact) 64.dp else 70.dp) else 12.dp)
                        .zIndex(5f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            stringResource(R.string.back),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.showTopBar,
            modifier = Modifier.align(Alignment.TopCenter).zIndex(6f),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                initialOffsetY = { -it }
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                targetOffsetY = { -it }
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            ReaderTopBar(
                state = state,
                colors = colors,
                onBack = onBack,
                onOpenToc = { viewModel.setShowToc(true) },
                onTheme = viewModel::setTheme,
                onFontSize = viewModel::setFontSize,
                onLineHeight = viewModel::setLineHeight,
                onFont = viewModel::setFont,
                onEyeCare = viewModel::setEyeCare,
                onToggleAi = {
                    viewModel.setPaneMode(
                        if (state.paneMode == AiPaneMode.MINIMIZED) {
                            AiPaneMode.DOCKED
                        } else {
                            AiPaneMode.MINIMIZED
                        }
                    )
                },
                onSearch = viewModel::openSearch,
                onToggleBookmark = viewModel::toggleBookmark,
                onTogglePdfMode = if (state.isPdf) {
                    { viewModel.setPdfOriginalPages(!state.pdfOriginalPages) }
                } else {
                    null
                },
                onOpenAppearance = onOpenAppearance,
                typefaceFor = rememberTypefaceResolver(),
                onImportFont = viewModel::importFont,
                onDeleteFont = viewModel::deleteFont,
                compact = compact
            )
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().zIndex(6f)) {
        AnimatedVisibility(
            visible = state.showTopBar,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
                initialOffsetY = { it }
            ) + fadeIn(animationSpec = tween(durationMillis = 160)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                targetOffsetY = { it }
            ) + fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 4.dp else 6.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            ReaderPageBar(
                state = state,
                colors = colors,
                onPrevious = {
                    if (state.showPdfPages) viewModel.onPdfPageSettled(state.currentChapterIndex - 1)
                    else viewModel.controller.previousPage()
                },
                onNext = {
                    if (state.showPdfPages) viewModel.onPdfPageSettled(state.currentChapterIndex + 1)
                    else viewModel.controller.nextPage()
                },
                onPreviousChapter = viewModel::previousChapter,
                onNextChapter = viewModel::nextChapter,
                onOpenToc = { viewModel.setShowToc(true) }
            )
        }
        }
        ReaderProgressLine(
            progress = state.progress,
            colors = colors
        )
        }
    }
}

@Composable
private fun LoadingOverlay(colors: com.termino.core.reader.ReaderColors) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.accent)
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.book_preparing),
                fontSize = 13.sp,
                color = colors.muted
            )
        }
    }
}

@Composable
private fun ReaderErrorOverlay(
    message: String,
    colors: com.termino.core.reader.ReaderColors,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(28.dp),
            shape = RoundedCornerShape(18.dp),
            color = colors.surface,
            contentColor = colors.foreground,
            shadowElevation = 10.dp
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.book_open_failed),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.foreground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Button(onClick = onBack) {
                    Text(stringResource(R.string.back_to_library))
                }
            }
        }
    }
}

/**
 * Window behavior while reading: screen kept on, immersive mode (system bars
 * hidden) while the reader UI is hidden, and page turns with the volume keys.
 */
@Composable
private fun ReaderWindowEffects(
    state: com.termino.ui.reader.ReaderUiState,
    viewModel: ReaderViewModel
) {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val activity = view.context.findActivity()
    DisposableEffect(state.showTopBar, state.theme, activity) {
        val window = activity?.window
        if (window != null) {
            val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
            // Status bar icons follow the reader theme, not the system theme.
            val dark = com.termino.core.reader.readerColors(state.theme).isDark
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (state.showTopBar) {
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
                controller.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                // Leaving the reader restores the app theme appearance.
                val nightMode = (view.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                controller.isAppearanceLightStatusBars = !nightMode
                controller.isAppearanceLightNavigationBars = !nightMode
            }
        }
    }
    DisposableEffect(state.volumeKeysTurnPages, state.showPdfPages, state.currentChapterIndex) {
        if (state.volumeKeysTurnPages) {
            com.termino.core.reader.ReaderKeys.pageTurner = { forward ->
                viewModel.onUserPageTurn()
                if (state.showPdfPages) {
                    viewModel.onPdfPageSettled(state.currentChapterIndex + if (forward) 1 else -1)
                } else if (forward) {
                    viewModel.controller.nextPage()
                } else {
                    viewModel.controller.previousPage()
                }
                true
            }
        } else {
            com.termino.core.reader.ReaderKeys.pageTurner = null
        }
        onDispose { com.termino.core.reader.ReaderKeys.pageTurner = null }
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun rememberTypefaceResolver(): (com.termino.core.reader.ReaderFont) -> android.graphics.Typeface {
    val app = LocalContext.current.applicationContext as com.termino.TerminoApp
    return remember(app) { app.serviceLocator.fontRepository::typeface }
}
