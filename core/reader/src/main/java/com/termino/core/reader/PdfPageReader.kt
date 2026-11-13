package com.termino.core.reader

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Page renderer backed by the system PdfRenderer. PdfRenderer is not thread
 * safe: every operation goes through a Mutex. Rendered pages are kept in a
 * byte-sized LRU cache.
 */
class PdfPageRenderer(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)
    private val mutex = Mutex()
    private var closed = false
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    val pageCount: Int get() = renderer.pageCount

    /** Page aspect ratio (to reserve space before rendering). */
    suspend fun aspectRatio(index: Int): Float = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock 0.707f
            val page = renderer.openPage(index)
            try {
                page.width.toFloat() / page.height.coerceAtLeast(1)
            } finally {
                page.close()
            }
        }
    }

    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val width = widthPx.coerceIn(200, MAX_WIDTH)
        val key = "$index@$width"
        cache.get(key)?.let { return@withContext it }
        mutex.withLock {
            if (closed || index !in 0 until renderer.pageCount) return@withLock null
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt()
                    val bitmap = Bitmap.createBitmap(width, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    page.close()
                }
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }

    override fun close() {
        runBlocking {
            mutex.withLock {
                closed = true
                cache.evictAll()
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
    }

    private companion object {
        const val CACHE_BYTES = 48 * 1024 * 1024
        const val MAX_WIDTH = 3000
    }
}

/**
 * PDF reader showing the original page: native horizontal pager,
 * pinch to zoom, double tap to toggle zoom and the same tap zones as the text
 * reader (edges = turn page, center = toggle UI).
 */
@Composable
fun PdfPageReader(
    filePath: String,
    currentPage: Int,
    colors: ReaderColors,
    theme: ReaderTheme,
    tapZones: Boolean,
    onPageSettled: (Int) -> Unit,
    onCenterTap: () -> Unit,
    onUserPageTurn: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val renderer = remember(filePath) {
        runCatching { PdfPageRenderer(File(filePath)) }.getOrNull()
    }
    DisposableEffect(renderer) {
        onDispose { renderer?.close() }
    }
    if (renderer == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.reader_pdf_open_error), color = colors.muted)
        }
        return
    }
    val pageCount = remember(renderer) { renderer.pageCount }
    val pagerState = rememberPagerState(initialPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) {
        pageCount
    }
    val scope = rememberCoroutineScope()
    val latestSettled by rememberUpdatedState(onPageSettled)

    // External jumps (TOC, search, buttons): adjacent page animated, distant page instant.
    LaunchedEffect(currentPage) {
        val target = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (pagerState.settledPage != target && !pagerState.isScrollInProgress) {
            if (kotlin.math.abs(pagerState.currentPage - target) == 1) {
                pagerState.animateScrollToPage(target)
            } else {
                pagerState.scrollToPage(target)
            }
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { latestSettled(it) }
    }

    var zoomed by remember { mutableStateOf(false) }
    val pageFilter = remember(theme) { pageColorFilter(theme) }

    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        userScrollEnabled = !zoomed,
        beyondViewportPageCount = 1,
        pageSpacing = 12.dp,
        key = { it }
    ) { page ->
        ZoomablePdfPage(
            renderer = renderer,
            index = page,
            isCurrent = page == pagerState.settledPage,
            colorFilter = pageFilter,
            colors = colors,
            onZoomChanged = { if (page == pagerState.settledPage) zoomed = it },
            onTap = { fraction ->
                when {
                    tapZones && fraction < 0.28f && page > 0 -> {
                        onUserPageTurn()
                        scope.launch { pagerState.animateScrollToPage(page - 1) }
                    }
                    tapZones && fraction > 0.72f && page < pageCount - 1 -> {
                        onUserPageTurn()
                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                    }
                    else -> onCenterTap()
                }
            }
        )
    }
}

@Composable
private fun ZoomablePdfPage(
    renderer: PdfPageRenderer,
    index: Int,
    isCurrent: Boolean,
    colorFilter: ColorFilter?,
    colors: ReaderColors,
    onZoomChanged: (Boolean) -> Unit,
    onTap: (fraction: Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val boxWidth = constraints.maxWidth.toFloat()
        val boxHeight = constraints.maxHeight.toFloat()

        val ratio by produceState(initialValue = 0.707f, renderer, index) {
            value = renderer.aspectRatio(index)
        }
        val bitmap by produceState<Bitmap?>(initialValue = null, renderer, index, widthPx) {
            value = renderer.render(index, widthPx)
        }

        val scale = remember { Animatable(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val scope = rememberCoroutineScope()

        // When zoomed, re-render at a higher resolution (in 0.5x steps) so text
        // stays sharp instead of scaling up the base bitmap.
        val zoomStep by remember {
            androidx.compose.runtime.derivedStateOf {
                if (scale.value < 1.2f) 1f else (kotlin.math.round(scale.value.coerceAtMost(2.5f) * 2f) / 2f)
            }
        }
        val hiRes by produceState<Bitmap?>(initialValue = null, renderer, index, widthPx, zoomStep) {
            value = if (zoomStep > 1f) {
                kotlinx.coroutines.delay(160)
                renderer.render(index, (widthPx * zoomStep).toInt().coerceAtMost(2400))
            } else {
                null
            }
        }

        // Zoom resets when leaving the page.
        LaunchedEffect(isCurrent) {
            if (!isCurrent && scale.value != 1f) {
                scale.snapTo(1f)
                offset = Offset.Zero
            }
        }
        LaunchedEffect(scale.value > 1.01f) { onZoomChanged(scale.value > 1.01f) }

        fun clamp(value: Offset, s: Float): Offset {
            val maxX = (boxWidth * (s - 1f)) / 2f
            val maxY = (boxHeight * (s - 1f)) / 2f
            return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Two-finger pinch, or one-finger drag when already zoomed.
                    // At 1x, a single finger is left to the pager to turn pages.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed > 1 || scale.value > 1.01f) {
                                val zoomChange = event.calculateZoom()
                                val pan = event.calculatePan()
                                val newScale = (scale.value * zoomChange).coerceIn(1f, MAX_ZOOM)
                                scope.launch { scale.snapTo(newScale) }
                                offset = clamp(offset + pan, newScale)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            scope.launch {
                                if (scale.value > 1.01f) {
                                    scale.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
                                    offset = Offset.Zero
                                } else {
                                    val target = DOUBLE_TAP_ZOOM
                                    val center = Offset(boxWidth / 2f, boxHeight / 2f)
                                    offset = clamp((center - tap) * (target - 1f), target)
                                    scale.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            }
                        },
                        onTap = { tap ->
                            if (scale.value <= 1.01f) onTap(tap.x / boxWidth.coerceAtLeast(1f))
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio, matchHeightConstraintsFirst = boxWidth / boxHeight > ratio)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .shadow(6.dp, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (colors.isDark) Color(0xFF202020) else Color.White),
                contentAlignment = Alignment.Center
            ) {
                // In dark themes, mostly-image pages (covers, photos) are not
                // inverted (they would look like a negative): they are only dimmed.
                val photoPage = remember(bitmap) { bitmap?.let(::looksLikePhotoPage) ?: false }
                val effectiveFilter = if (photoPage && colors.isDark) DIM_FILTER else colorFilter
                Crossfade(targetState = hiRes ?: bitmap, animationSpec = tween(220), label = "pdf-page") { image ->
                    if (image != null) {
                        Image(
                            bitmap = image.asImageBitmap(),
                            contentDescription = stringResource(R.string.reader_pdf_page, index + 1),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            colorFilter = effectiveFilter
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                        }
                    }
                }
            }
            Text(
                text = "${index + 1}",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
                fontSize = 10.sp,
                color = colors.muted.copy(alpha = 0.7f)
            )
        }
    }
}

private const val MAX_ZOOM = 5f

private val DIM_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.78f, 0f, 0f, 0f, 0f,
            0f, 0.78f, 0f, 0f, 0f,
            0f, 0f, 0.78f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

/**
 * Cheap sampling (≈ 48×64 points): a page is a "photo" if many pixels are
 * mid-tone or colored. Text pages are almost all white with some black.
 */
private fun looksLikePhotoPage(bitmap: Bitmap): Boolean {
    val cols = 48
    val rows = 64
    var busy = 0
    for (r in 0 until rows) {
        val y = (r + 0.5f) * bitmap.height / rows
        for (c in 0 until cols) {
            val x = (c + 0.5f) * bitmap.width / cols
            val p = bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1))
            val red = (p shr 16) and 0xFF
            val green = (p shr 8) and 0xFF
            val blue = p and 0xFF
            val max = maxOf(red, green, blue)
            val min = minOf(red, green, blue)
            val luma = (red * 299 + green * 587 + blue * 114) / 1000
            val colorful = max - min > 40
            val midTone = luma in 50..215
            if (colorful || midTone) busy++
        }
    }
    return busy > cols * rows * 0.35f
}
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Dark: inverts luminance (night mode). Sepia: tints the paper white. */
private fun pageColorFilter(theme: ReaderTheme): ColorFilter? = when (theme) {
    ReaderTheme.AUTO, ReaderTheme.LIGHT -> null
    ReaderTheme.BLACK -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.82f, 0f, 0f, 0f, 214f,
                0f, -0.82f, 0f, 0f, 210f,
                0f, 0f, -0.82f, 0f, 200f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    ReaderTheme.DARK -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                -0.85f, 0f, 0f, 0f, 235f,
                0f, -0.85f, 0f, 0f, 230f,
                0f, 0f, -0.85f, 0f, 222f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    ReaderTheme.SEPIA -> ColorFilter.colorMatrix(
        ColorMatrix(
            floatArrayOf(
                0.97f, 0f, 0f, 0f, 0f,
                0f, 0.92f, 0f, 0f, 0f,
                0f, 0f, 0.80f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
}
