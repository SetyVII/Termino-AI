package com.termino.core.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.Selection
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.text.style.SuperscriptSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderReference(
    val referenceKey: String,
    val numericKey: String?,
    val label: String,
    val content: String,
    val chapterIndex: Int,
    val anchor: String?
) {
    fun isSheetReference(): Boolean {
        val key = (anchor ?: referenceKey.substringAfter(':', referenceKey)).trim()
        val lower = key.lowercase()
        return lower.startsWith("fn") ||
            lower.startsWith("cr") ||
            lower.startsWith("aff") ||
            lower.startsWith("contact") ||
            lower.startsWith("note") ||
            lower.startsWith("bib") ||
            lower.contains("footnote") ||
            lower.contains("endnote") ||
            lower.contains("citation")
    }
}

fun isSheetAnchor(fragment: String): Boolean {
    val lower = fragment.trim().lowercase()
    return lower.startsWith("fn") ||
        lower.startsWith("cr") ||
        lower.startsWith("aff") ||
        lower.startsWith("contact") ||
        lower.startsWith("note") ||
        lower.startsWith("bib") ||
        lower.contains("footnote") ||
        lower.contains("endnote") ||
        lower.contains("citation")
}

data class ReaderContent(
    val requestId: Long,
    val chapterIndex: Int,
    val html: String,
    val initialPage: Int,
    val highlights: List<ReaderHighlight>,
    val references: List<ReaderReference> = emptyList()
)

interface ReaderInteractionListener {
    fun onReady(totalPages: Int, page: Int, visibleText: String)
    fun onPageChanged(page: Int, totalPages: Int, visibleText: String)
    fun onSelectionCleared()
    fun onHighlightRequested(text: String, start: Int, end: Int, color: HighlightColor)
    fun onDictionaryRequested(text: String, start: Int, end: Int)
    fun onExplainRequested(text: String, start: Int, end: Int)
    fun onAskRequested(text: String, start: Int, end: Int)
    fun onHighlightTapped(id: String, text: String)
    fun onHighlightApplied(id: String, success: Boolean)
    fun onHighlightRemoved(id: String, success: Boolean)
    fun onReferenceTapped(references: List<ReaderReference>)
    fun onImageTapped(imageData: ByteArray?, imageUri: String)
    fun onInternalChapterTapped(chapterIndex: Int, anchor: String?, label: String)
    fun onEndOfChapter()
    fun onStartOfChapter()
    fun onTap()
    /** Whether taps on the edges turn the page. */
    fun tapZonesEnabled(): Boolean = true
    /** Page turn by gesture (edge tap, swipe or volume key). */
    fun onUserPageTurn() {}
    /** Provisional font size while pinching (null when finished). */
    fun onFontSizePreview(size: Int?) {}
    /** Pinch finished: new font size. */
    fun onFontSizeGesture(size: Int) {}
    fun onLinkTapped(url: String)
}

private data class ReaderStyle(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.65f,
    val font: ReaderFont = ReaderFont.SERIF
)

/**
 * Native reading engine: renders a chapter's HTML into a TextView with
 * Spannable (highlights, references, images, internal links), paginates by
 * viewport and reports everything through [ReaderInteractionListener].
 *
 * @param assetDir folder holding the book's images (relative paths in the
 * HTML are resolved against it).
 */
class NativeReaderController(
    private val context: Context,
    private val assetDir: java.io.File
) {
    companion object {
        private const val ACTION_YELLOW = 1001
        private const val ACTION_GREEN = 1002
        private const val ACTION_BLUE = 1003
        private const val ACTION_PINK = 1007
        private const val ACTION_PURPLE = 1008
        private const val ACTION_DEFINE = 1004
        private const val ACTION_EXPLAIN = 1005
        private const val ACTION_ASK = 1006

        // "[5]", "[ 5 ]", "[13, 18]", "[3-5]", "[ 7 – 9 ]": optional spaces inside the brackets.
        private val REFERENCE_PATTERN = Regex("\\[\\s*(\\d+(?:\\s*[,;–-]\\s*\\d+)*)\\s*]")
        private val REFERENCE_RANGE = Regex("(\\d+)\\s*[-,–]\\s*(\\d+)")
        private val REFERENCE_NUMBER = Regex("\\d+")
        // Technical EPUBs use NBSP in section numbers ("11.2\u00A0").
        // It is normalized to a space (1:1, offsets unchanged) before searching.
        private val NBSP_REGEX = Regex("[\\u00A0\\u2007\\u202F]")
        private const val MAX_RANGE_EXPANSION = 12
        private const val PAGE_OUT_MS = 140L
        private const val PAGE_IN_MS = 180L
        private val ANCHOR_TARGET_TAG_REGEX = Regex(
            """(<([a-zA-Z0-9]+)(?:\s+[^>]*)?\sid\s*=\s*["']([^"']+)["'][^>]*>)"""
        )
    }

    var listener: ReaderInteractionListener? = null

    /** Resolves the chosen font (system or imported) to a Typeface. */
    var typefaceResolver: ((ReaderFont) -> Typeface)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var textView: TextView? = null
    private var scrollView: NestedScrollView? = null
    private var content: ReaderContent? = null
    private var style = ReaderStyle()
    private val highlightSpans = LinkedHashMap<String, HighlightSpan>()
    // Images of the current chapter: original <img> src -> absolute path of
    // the file decoded by imageFor(). Lets a tap on an ImageSpan resolve to
    // the original file (instead of extracting the bitmap from the drawable
    // at runtime, which would lose quality).
    private val loadedImageSources = mutableMapOf<String, String>()
    private val imageCache = LruCache<String, Bitmap>(24)
    private var referencesByKey: Map<String, ReaderReference> = emptyMap()
    private var referencesByNumber: Map<String, ReaderReference> = emptyMap()
    // A book can contain both footnotes ("Fn1") and bibliography entries
    // ("CR1") with the same number. Superscripts must resolve to the footnote
    // and brackets [1] to the bibliography.
    private var footnoteByNumber: Map<String, ReaderReference> = emptyMap()
    private var citationByNumber: Map<String, ReaderReference> = emptyMap()
    private var referencesByAnchor: Map<String, ReaderReference> = emptyMap()
    // Exact character offsets for every element with an id in the chapter,
    // taken from the injected anchors. Allows jumping to any anchor in O(1)
    // without relying on heuristic text matching.
    private var anchorOffsets: Map<String, Int> = emptyMap()
    private var lastPage = -1
    private var lastTotal = -1
    private var renderedRequestId = -1L
    private var pageTurnToken = 0

    private fun normalizeSelection(view: TextView) {
        val rawStart = min(view.selectionStart, view.selectionEnd)
        val rawEnd = max(view.selectionStart, view.selectionEnd)
        if (rawStart < 0 || rawEnd <= rawStart) return
        val text = view.text
        if (rawEnd > text.length) return
        val selected = text.subSequence(rawStart, rawEnd).toString()
        if (selected.isNotBlank()) {
            // The user can drag the handles onto a space or a line break: trim
            // the ends so the highlight and the text sent to the AI carry no
            // stray whitespace. If there was only whitespace, fall back to word snapping.
            val trimmedStart = rawStart + selected.indexOfFirst { !it.isWhitespace() }
            val trimmedEnd = rawStart + selected.indexOfLast { !it.isWhitespace() } + 1
            if (trimmedStart < trimmedEnd && (trimmedStart != rawStart || trimmedEnd != rawEnd)) {
                val spannable = view.text as? Spannable ?: return
                Selection.setSelection(spannable, trimmedStart, trimmedEnd)
                view.invalidate()
            }
            return
        }

        var before = rawStart
        while (before > 0 && text[before - 1].isWhitespace()) before--
        if (before > 0) {
            var start = before
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            var end = before
            while (end < text.length && !text[end].isWhitespace()) end++
            if (start < end) {
                val spannable = view.text as? Spannable ?: return
                Selection.setSelection(spannable, start, end)
                view.invalidate()
                return
            }
        }

        var after = rawEnd
        while (after < text.length && text[after].isWhitespace()) after++
        if (after < text.length) {
            var start = after
            while (start > 0 && !text[start - 1].isWhitespace()) start--
            var end = after
            while (end < text.length && !text[end].isWhitespace()) end++
            if (start < end) {
                val spannable = view.text as? Spannable ?: return
                Selection.setSelection(spannable, start, end)
                view.invalidate()
            }
        }
    }

    private val selectionCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val view = textView
            if (view != null) normalizeSelection(view)
            val start = view?.selectionStart ?: -1
            val end = view?.selectionEnd ?: -1
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val view = textView
            if (view != null) normalizeSelection(view)
            val start = view?.selectionStart ?: -1
            val end = view?.selectionEnd ?: -1
            menu.clear()
            val highlightMenu = menu.addSubMenu(
                Menu.NONE, 0, 0, context.getString(R.string.reader_action_highlight)
            )
            // Shown as an always-visible icon: on narrow screens (~411dp) the
            // overflow menu clipped the nested color popup and hid its last
            // item. In the selection bar the 5-color popup has room to anchor.
            highlightMenu.item?.apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setIcon(android.R.drawable.ic_menu_more)
            }
            highlightMenu.add(Menu.NONE, ACTION_YELLOW, 0, context.getString(HighlightColor.YELLOW.labelRes))
            highlightMenu.add(Menu.NONE, ACTION_GREEN, 1, context.getString(HighlightColor.GREEN.labelRes))
            highlightMenu.add(Menu.NONE, ACTION_BLUE, 2, context.getString(HighlightColor.BLUE.labelRes))
            highlightMenu.add(Menu.NONE, ACTION_PINK, 3, context.getString(HighlightColor.PINK.labelRes))
            highlightMenu.add(Menu.NONE, ACTION_PURPLE, 4, context.getString(HighlightColor.PURPLE.labelRes))
            menu.add(Menu.NONE, ACTION_DEFINE, 1, context.getString(R.string.reader_action_define))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_EXPLAIN, 2, context.getString(R.string.reader_action_explain))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_ASK, 3, context.getString(R.string.reader_action_ask))
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val view = textView ?: return false
            val start = min(view.selectionStart, view.selectionEnd)
            val end = max(view.selectionStart, view.selectionEnd)
            if (start < 0 || end <= start || end > view.text.length) return false
            val text = view.text.subSequence(start, end).toString()
            when (item.itemId) {
                ACTION_YELLOW -> listener?.onHighlightRequested(
                    text, start, end, HighlightColor.YELLOW
                )
                ACTION_GREEN -> listener?.onHighlightRequested(
                    text, start, end, HighlightColor.GREEN
                )
                ACTION_BLUE -> listener?.onHighlightRequested(
                    text, start, end, HighlightColor.BLUE
                )
                ACTION_PINK -> listener?.onHighlightRequested(
                    text, start, end, HighlightColor.PINK
                )
                ACTION_PURPLE -> listener?.onHighlightRequested(
                    text, start, end, HighlightColor.PURPLE
                )
                ACTION_DEFINE -> listener?.onDictionaryRequested(text, start, end)
                ACTION_EXPLAIN -> listener?.onExplainRequested(text, start, end)
                ACTION_ASK -> listener?.onAskRequested(text, start, end)
                else -> return false
            }
            mode.finish()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            listener?.onSelectionCleared()
        }
    }

    fun attach(scroll: NestedScrollView, text: TextView) {
        scrollView = scroll
        textView = text
        configureViews()
        content?.let { pending ->
            if (renderedRequestId != pending.requestId) {
                scope.launch { load(pending) }
            }
        }
    }

    fun detach() {
        scope.coroutineContext.cancelChildren()
        handler.removeCallbacksAndMessages(null)
        textView = null
        scrollView = null
        renderedRequestId = -1L
    }

    suspend fun load(content: ReaderContent) {
        this.content = content
        if (renderedRequestId == content.requestId) return
        val reqId = content.requestId
        renderedRequestId = reqId

        val prepared = withContext(Dispatchers.Default) {
            val imageSources = mutableMapOf<String, String>()
            val imageGetter = Html.ImageGetter { source ->
                imageFor(source, imageSources)
            }
            val htmlToRender = injectAnchorTargets(content.html)
            val spanned = Html.fromHtml(
                htmlToRender,
                Html.FROM_HTML_MODE_COMPACT,
                imageGetter,
                null
            )
            val builder = SpannableStringBuilder(spanned)
            val anchors = mutableMapOf<String, Int>()
            val urlSpans = builder.getSpans(0, builder.length, URLSpan::class.java)
            for (span in urlSpans) {
                val url = span.url ?: continue
                if (url.startsWith("termino://target-anchor/")) {
                    val anchorId = url.removePrefix("termino://target-anchor/").trim().lowercase()
                    val start = builder.getSpanStart(span)
                    if (start >= 0 && !anchors.containsKey(anchorId)) {
                        anchors[anchorId] = start
                    }
                    builder.removeSpan(span)
                }
            }
            val currentChapterRefs = content.references.filter {
                it.chapterIndex == content.chapterIndex
            }
            val refByAnchor = currentChapterRefs
                .mapNotNull { reference ->
                    reference.anchor?.lowercase()?.let { anchor -> anchor to reference }
                }
                .toMap()
            val fnByNum = currentChapterRefs
                .filter {
                    it.anchor?.startsWith("fn", ignoreCase = true) == true ||
                        it.referenceKey.contains(":fn", ignoreCase = true)
                }
                .mapNotNull { reference -> reference.numericKey?.let { it to reference } }
                .toMap()
            val citByNum = content.references
                .filter {
                    it.anchor?.startsWith("CR", ignoreCase = true) == true ||
                        it.referenceKey.startsWith("CR", ignoreCase = true)
                }
                .mapNotNull { reference -> reference.numericKey?.let { it to reference } }
                .toMap()
            val refByNum = content.references
                .mapNotNull { reference -> reference.numericKey?.let { it to reference } }
                .toMap()
            val refByKey = content.references.associateBy { it.referenceKey.lowercase() }

            val hlSpans = LinkedHashMap<String, HighlightSpan>()
            content.highlights.forEach { highlight ->
                val span = HighlightSpan(
                    id = highlight.id,
                    colorKey = highlight.color,
                    color = highlightColor(highlight.color),
                    listener = listener
                )
                hlSpans[highlight.id] = span
                val start = highlight.startOffset.coerceIn(0, builder.length)
                val end = highlight.endOffset.coerceIn(0, builder.length)
                if (end > start) {
                    builder.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            addReferenceSpansInternal(
                builder,
                refByKey,
                refByNum,
                fnByNum,
                citByNum,
                readerColors(style.theme).accent.toArgb()
            )

            PreparedRender(
                builder = builder,
                anchors = anchors,
                referencesByKey = refByKey,
                referencesByAnchor = refByAnchor,
                footnoteByNumber = fnByNum,
                citationByNumber = citByNum,
                referencesByNumber = refByNum,
                highlightSpans = hlSpans,
                imageSources = imageSources
            )
        }

        if (renderedRequestId != reqId) return
        val view = textView ?: return
        val scroll = scrollView ?: return

        anchorOffsets = prepared.anchors
        referencesByKey = prepared.referencesByKey
        referencesByAnchor = prepared.referencesByAnchor
        footnoteByNumber = prepared.footnoteByNumber
        citationByNumber = prepared.citationByNumber
        referencesByNumber = prepared.referencesByNumber
        loadedImageSources.clear()
        loadedImageSources.putAll(prepared.imageSources)
        highlightSpans.clear()
        highlightSpans.putAll(prepared.highlightSpans)

        applyTypography()
        view.setText(prepared.builder, TextView.BufferType.SPANNABLE)
        view.setTextIsSelectable(true)
        view.customSelectionActionModeCallback = selectionCallback
        cancelPageAnimation()
        scroll.alpha = 1f
        updateHighlightColors()
        view.post {
            val total = totalPages()
            val target = content.initialPage.coerceIn(0, total - 1)
            scroll.scrollTo(0, target * viewportHeight())
            reportPage(force = true, ready = true)
        }
    }

    fun updateStyle(
        theme: ReaderTheme,
        fontSize: Int,
        lineHeight: Float,
        font: ReaderFont
    ) {
        style = style.copy(
            theme = theme,
            fontSize = fontSize,
            lineHeight = lineHeight,
            font = font
        )
        val view = textView ?: return
        val scroll = scrollView ?: return
        // Remember the first visible character (not pixels): when the size or
        // font changes the text reflows and we stay on the same sentence.
        val anchorOffset = view.layout?.let { layout ->
            val local = (scroll.scrollY - view.totalPaddingTop).coerceAtLeast(0)
            layout.getLineStart(layout.getLineForVertical(local))
        }
        val oldScroll = scroll.scrollY
        applyTypography()
        updateHighlightColors()
        view.post {
            val layout = view.layout
            if (anchorOffset != null && layout != null && anchorOffset > 0) {
                val line = layout.getLineForOffset(anchorOffset.coerceAtMost(view.text.length))
                scroll.scrollTo(0, (layout.getLineTop(line) + view.totalPaddingTop - dp(6)).coerceAtLeast(0))
            } else {
                scroll.scrollTo(0, oldScroll)
            }
            reportPage(force = true)
        }
    }


    private var pinchBaseSize = 0

    fun beginPinch(focusX: Float, focusY: Float) {
        val scroll = scrollView ?: return
        cancelPageAnimation()
        clearSelection()
        pinchBaseSize = style.fontSize
        scroll.pivotX = focusX
        scroll.pivotY = focusY
    }

    /** Instant preview: scales the view (cheap) and shows the size. */
    fun updatePinch(factor: Float) {
        val scroll = scrollView ?: return
        if (pinchBaseSize <= 0) return
        val size = pinchTargetSize(factor)
        val visual = size.toFloat() / pinchBaseSize
        scroll.scaleX = visual
        scroll.scaleY = visual
        listener?.onFontSizePreview(size)
    }

    fun endPinch(factor: Float) {
        val scroll = scrollView ?: return
        if (pinchBaseSize <= 0) return
        val size = pinchTargetSize(factor)
        pinchBaseSize = 0
        scroll.animate().cancel()
        scroll.scaleX = 1f
        scroll.scaleY = 1f
        listener?.onFontSizePreview(null)
        if (size != style.fontSize) {
            scroll.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            listener?.onFontSizeGesture(size)
        }
    }

    private fun pinchTargetSize(factor: Float): Int =
        kotlin.math.round(pinchBaseSize * factor).toInt().coerceIn(
            com.termino.core.reader.MIN_FONT_SIZE,
            com.termino.core.reader.MAX_FONT_SIZE
        )

    /**
     * True if the TextView has an active text selection. ReaderGestureLayout
     * uses it to avoid stealing the gesture while the user drags the
     * selection handles.
     */
    fun hasActiveSelection(): Boolean =
        (textView?.selectionStart ?: 0) != (textView?.selectionEnd ?: 0)

    fun nextPage() {
        val view = textView ?: return
        val scroll = scrollView ?: return
        val viewport = viewportHeight()
        if (viewport <= 0) return
        if (scroll.scrollY + viewport >= view.height - dp(2)) {
            listener?.onEndOfChapter()
            return
        }
        // The line cut by the bottom edge becomes the first line of the next
        // page: no line is shown split or skipped.
        val raw = scroll.scrollY + viewport
        turnPage(
            direction = 1,
            targetY = snapToLineTop(view, raw, preferNext = false)
                .coerceAtLeast(scroll.scrollY + 1)
                .coerceAtMost(view.height - viewport)
        )
    }

    fun previousPage() {
        val view = textView ?: return
        val scroll = scrollView ?: return
        val viewport = viewportHeight()
        if (viewport <= 0) return
        if (scroll.scrollY <= dp(2)) {
            listener?.onStartOfChapter()
            return
        }
        val raw = scroll.scrollY - viewport
        turnPage(
            direction = -1,
            targetY = if (raw <= 0) 0 else snapToLineTop(view, raw, preferNext = true)
        )
    }

    /**
     * Snaps [y] (scroll coordinate) to the start of a line. With [preferNext]
     * the next line is chosen when [y] falls mid-line, so the page never
     * starts with a cut line.
     */
    private fun snapToLineTop(view: TextView, y: Int, preferNext: Boolean): Int {
        val layout = view.layout ?: return y
        val top = view.totalPaddingTop
        val local = (y - top).coerceAtLeast(0)
        val line = layout.getLineForVertical(local)
        val lineTop = layout.getLineTop(line)
        val snapped = if (preferNext && lineTop < local && line + 1 < layout.lineCount) {
            layout.getLineTop(line + 1)
        } else {
            lineTop
        }
        // Small top margin so the line does not touch the edge.
        return (snapped + top - dp(6)).coerceAtLeast(0)
    }

    private fun turnPage(direction: Int, targetY: Int) {
        val scroll = scrollView ?: return
        if (viewportHeight() <= 0) return
        scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Cancel any previous page turn (including its withEndAction) and the render() fade.
        pageTurnToken++
        val token = pageTurnToken
        scroll.animate().cancel()
        val horizontal = dp(16).toFloat()
        val vertical = dp(6).toFloat()
        scroll.animate()
            .translationX(-direction * horizontal)
            .translationY(-direction * vertical)
            .alpha(0.55f)
            .setDuration(PAGE_OUT_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (token != pageTurnToken) return@withEndAction
                scroll.scrollTo(0, targetY)
                scroll.translationX = direction * horizontal
                scroll.translationY = direction * vertical
                scroll.animate()
                    .translationX(0f)
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(PAGE_IN_MS)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
                scheduleReport()
            }
            .start()
    }

    private fun cancelPageAnimation() {
        pageTurnToken++
        val scroll = scrollView ?: return
        scroll.animate().cancel()
        scroll.translationX = 0f
        scroll.translationY = 0f
        scroll.alpha = 1f
    }

    fun goToPage(page: Int) {
        val scroll = scrollView ?: return
        val viewport = viewportHeight()
        if (viewport <= 0) return
        val target = page.coerceIn(0, totalPages() - 1)
        cancelPageAnimation()
        scroll.scrollTo(0, target * viewport)
        scheduleReport()
    }

    fun applyHighlight(id: String, color: String, start: Int, end: Int) {
        val view = textView
        val spannable = view?.text as? Spannable
        if (view == null || spannable == null) {
            listener?.onHighlightApplied(id, false)
            return
        }
        val applied = addHighlightSpan(spannable, id, color, start, end)
        view.invalidate()
        listener?.onHighlightApplied(id, applied)
    }

    fun removeHighlight(id: String) {
        val view = textView ?: return
        val spannable = view.text as? Spannable ?: return
        val span = highlightSpans.remove(id)
        if (span == null) {
            listener?.onHighlightRemoved(id, false)
            return
        }
        spannable.removeSpan(span)
        view.invalidate()
        listener?.onHighlightRemoved(id, true)
    }

    fun goToHighlight(id: String) {
        val view = textView ?: return
        val scroll = scrollView ?: return
        val spannable = view.text as? Spanned ?: return
        val span = highlightSpans[id] ?: return
        val start = spannable.getSpanStart(span)
        if (start < 0) return
        val layout = view.layout ?: return
        val line = layout.getLineForOffset(start)
        val y = layout.getLineTop(line) + view.totalPaddingTop
        scroll.smoothScrollTo(0, (y - viewportHeight() / 3).coerceAtLeast(0))
    }

    fun clearSelection() {
        val view = textView ?: return
        view.clearFocus()
        val spannable = view.text as? Spannable ?: return
        Selection.setSelection(spannable, 0)
        view.invalidate()
    }

    private fun configureViews() {
        val view = textView ?: return
        val scroll = scrollView ?: return
        view.setTextIsSelectable(true)
        view.customSelectionActionModeCallback = selectionCallback
        view.isVerticalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.setHorizontallyScrolling(false)
        view.includeFontPadding = false
        // SIMPLE + NONE: instant O(N) layout, without Knuth-Plass line breaking
        // or a per-word hyphenation dictionary lookup.
        view.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        view.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        view.justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
        view.setPadding(dp(28), dp(22), dp(28), dp(72))
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleReport() }
        // Horizontal swipes are intercepted by the root ReaderGestureLayout
        // (see NativeReaderView); here the TextView only handles taps on
        // references, highlights, images and links.
        view.setOnTouchListener { touched, event ->
            highlightTapListener.onTouch(touched, event)
        }
        applyTypography()
    }

    private fun injectAnchorTargets(html: String): String {
        // Un-nest any target anchor left inside an <a> by earlier parsing (in
        // HTML an <a> inside another <a> closes the outer one early, dropping its URLSpan).
        val unnested = html.replace(Regex("""(<a\s+[^>]*>)\s*(<a\s+href=["']termino://target-anchor/[^"']+["']>&#x200B;</a>)""")) { match ->
            match.groupValues[2] + match.groupValues[1]
        }
        if (unnested.contains("termino://target-anchor/")) return unnested
        return unnested.replace(ANCHOR_TARGET_TAG_REGEX) { match ->
            val fullTag = match.groupValues[1]
            val tagName = match.groupValues[2]
            val id = match.groupValues[3].trim()
            if (id.isNotBlank()) {
                if (tagName.equals("a", ignoreCase = true)) {
                    // Never nest an <a> inside another <a>: insert it as the previous sibling
                    "<a href=\"termino://target-anchor/$id\">&#x200B;</a>$fullTag"
                } else {
                    "$fullTag<a href=\"termino://target-anchor/$id\">&#x200B;</a>"
                }
            } else {
                fullTag
            }
        }
    }



    private fun applyTypography() {
        val view = textView ?: return
        val scroll = scrollView ?: return
        val colors = readerColors(style.theme)
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.toFloat())
        view.setLineSpacing(0f, style.lineHeight)
        view.typeface = typefaceResolver?.invoke(style.font) ?: Typeface.SERIF
        view.setTextColor(colors.foreground.toArgb())
        view.setBackgroundColor(colors.background.toArgb())
        view.setLinkTextColor(colors.accent.toArgb())
        view.highlightColor = android.graphics.Color.argb(110, 169, 85, 47)
        view.setPadding(dp(28), dp(22), dp(28), dp(72))
        scroll.setBackgroundColor(colors.background.toArgb())
    }

    private fun updateHighlightColors() {
        val view = textView ?: return
        highlightSpans.values.forEach { span ->
            span.color = highlightColor(span.colorKey)
        }
        val accent = readerColors(style.theme).accent.toArgb()
        val text = view.text as? Spanned
        if (text != null) {
            text.getSpans(0, text.length, ReferenceSpan::class.java).forEach { span ->
                span.color = accent
            }
        }
        view.invalidate()
    }

    private fun addHighlightSpan(
        spannable: Spannable,
        id: String,
        colorKey: String,
        start: Int,
        end: Int
    ): Boolean {
        if (start < 0 || end <= start || end > spannable.length) return false
        val span = HighlightSpan(
            id = id,
            colorKey = colorKey,
            color = highlightColor(colorKey),
            listener = listener
        )
        spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlightSpans[id] = span
        return true
    }

    private fun highlightColor(colorKey: String): Int {
        val colors = readerColors(style.theme)
        return when (HighlightColor.fromKey(colorKey)) {
            HighlightColor.YELLOW -> colors.highlightYellow.toArgb()
            HighlightColor.GREEN -> colors.highlightGreen.toArgb()
            HighlightColor.BLUE -> colors.highlightBlue.toArgb()
            HighlightColor.PINK -> colors.highlightPink.toArgb()
            HighlightColor.PURPLE -> colors.highlightPurple.toArgb()
        }
    }

    /**
     * Decodes the image referenced by [source] and scales it to the reader
     * column width (the measured TextView width; before the first layout, 55%
     * of the screen is used as a fallback so the image fits even with the AI
     * assistant docked). It also records in [loadedImageSources] the file
     * backing each src, so tapping the ImageSpan can open the full-size image
     * without re-extracting it from the drawable.
     */
    private fun imageFor(source: String, imageSources: MutableMap<String, String>? = null): Drawable? {
        val clean = source.substringAfterLast("assets/").substringBefore('?').substringBefore('#')
        if (clean.isBlank()) return null
        val file = File(assetDir, clean)
        if (!file.isFile) return null
        var bitmap = imageCache.get(file.absolutePath)
        if (bitmap == null || bitmap.isRecycled) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val maxDimension = (context.resources.displayMetrics.widthPixels * 1.2f).toInt().coerceAtLeast(1)
            var sample = 1
            while ((bounds.outWidth / (sample * 2)) >= maxDimension) {
                sample *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath, decodeOptions) }.getOrNull()
            if (bitmap != null) {
                imageCache.put(file.absolutePath, bitmap)
            }
        }
        if (bitmap == null) return null
        if (imageSources != null) {
            imageSources[source] = file.absolutePath
        } else {
            loadedImageSources[source] = file.absolutePath
        }
        // The available width is the reader column (the measured TextView),
        // not the full screen: with the AI assistant docked the panel is much
        // narrower than the screen, and using widthPixels cropped images
        // outside the column. If the TextView is not measured yet (first
        // render, before the first layout), 55% of the screen guarantees the
        // image fits even with the panel docked.
        val available = textView?.width?.takeIf { it > 0 }
            ?: (context.resources.displayMetrics.widthPixels * 0.55f).toInt()
        val maxWidth = (available - dp(56)).coerceAtLeast(dp(120))
        val scale = min(1f, maxWidth.toFloat() / bitmap.width.coerceAtLeast(1))
        val drawable = BitmapDrawable(context.resources, bitmap)
        drawable.setBounds(
            0,
            0,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1)
        )
        return drawable
    }

    /**
     * Resolves an internal link (`#Aff8`, `#Sec5`, `#Fn1`, `#CR8`...).
     *
     * - If the anchor is a reference shown in the sheet (note, citation,
     *   affiliation, contact), opens the sheet. This keeps an author's "(1)"
     *   superscript from landing on footnote "1".
     * - If it is a section/figure/table/bibliography, scrolls to the heading
     *   text using the semantic content extracted from the EPUB (normalizing
     *   NBSP), not just the link's visible label.
     * - If the anchor looks like a reference but was not found, returns false
     *   so the UI shows a notice instead of searching for a bare "1".
     */
    fun goToInternalAnchor(fragment: String, label: String, allowSheet: Boolean = true): Boolean {
        val view = textView ?: return false
        clearSelection()

        val cleanFragment = fragment.trim().lowercase()
        val reference = referencesByAnchor[cleanFragment]
        if (allowSheet) {
            if (reference != null) {
                if (reference.isSheetReference()) {
                    listener?.onReferenceTapped(listOf(reference))
                    return true
                }
            }
            if (isSheetAnchor(cleanFragment)) {
                if (reference != null) {
                    listener?.onReferenceTapped(listOf(reference))
                    return true
                }
                return false
            }
        }

        // Exact direct jump through AnchorSpan in O(1)
        val exactOffset = anchorOffsets[cleanFragment]
        if (exactOffset != null) {
            return scrollToOffset(exactOffset)
        }

        // Fallback: semantic text search
        if (reference != null) {
            if (scrollToText(reference.content, reference.label)) return true
        }
        return scrollToText(label, cleanFragment)
    }

    fun scrollToY(y: Int): Boolean {
        val scroll = scrollView ?: return false
        scroll.scrollTo(0, y.coerceAtLeast(0))
        scheduleReport()
        return true
    }

    fun currentScrollY(): Int = scrollView?.scrollY ?: 0

    /**
     * Finds the text of a section/figure/table in the rendered chapter. It
     * first tries the full semantic content ("6.2 Optimization Settings") and,
     * if missing, the heading number at the start of a line ("6.2"). NBSP is
     * normalized to a space without changing offsets.
     */
    private fun scrollToText(primary: String, fallback: String): Boolean {
        val view = textView ?: return false
        view.layout ?: return false
        val haystack = view.text.toString().replace(NBSP_REGEX, " ")
        val needles = buildList {
            add(primary)
            // A table/figure caption may end with a citation that does not
            // match the rendered text exactly; trying its prefix
            // ("Table 3.1 ...") avoids landing on section 3.1.
            val prefix = primary.take(60).trim()
            if (prefix.length >= 12 && prefix != primary) add(prefix)
            // Short label ("Table 3.1", "Figure 6.1"): survives the line
            // breaks Html.fromHtml inserts into long captions.
            val captionLabel = Regex(
                "(?i)\\b(Table|Figure|Tabla|Figura)\\s+\\d+(?:\\.\\d+)*"
            ).find(primary)?.value
            if (captionLabel != null) add(captionLabel)
            add(fallback)
        }
            .map { it.replace(NBSP_REGEX, " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()
        for (needle in needles) {
            val exact = haystack.indexOf(needle, ignoreCase = true)
            if (exact >= 0) {
                return scrollToOffset(exact)
            }
            val numeric = Regex("\\d+(?:\\.\\d+)+").find(needle)?.value
            if (numeric != null) {
                val heading = Regex("(?m)^\\s*${Regex.escape(numeric)}(?:\\s|$)")
                val match = heading.find(haystack)
                if (match != null) {
                    return scrollToOffset(match.range.first + match.value.indexOf(numeric))
                }
            }
        }
        return false
    }

    private fun scrollToOffset(offset: Int): Boolean {
        val view = textView ?: return false
        val scroll = scrollView ?: return false
        val layout = view.layout ?: return false
        if (view.text.isEmpty()) return false
        val safeOffset = offset.coerceIn(0, view.text.length - 1)
        val line = layout.getLineForOffset(safeOffset)
        val y = layout.getLineTop(line) + view.totalPaddingTop
        val target = (y - viewportHeight() / 4).coerceAtLeast(0)
        // Direct jump: smoothScrollTo over content tens of thousands of pixels
        // tall did not finish in this NestedScrollView and the reader stayed on
        // the original page. scrollTo is the same mechanism pagination uses and
        // it updates the page report.
        scroll.scrollTo(0, target)
        scheduleReport()
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        return true
    }

    private fun addReferenceSpansInternal(
        spannable: Spannable,
        refByKey: Map<String, ReaderReference>,
        refByNum: Map<String, ReaderReference>,
        fnByNum: Map<String, ReaderReference>,
        citByNum: Map<String, ReaderReference>,
        accentColor: Int
    ) {
        if (refByKey.isEmpty() && refByNum.isEmpty()) return
        val text = spannable.toString()
        var found = 0
        REFERENCE_PATTERN.findAll(text).forEach { match ->
            val keys = referenceKeys(match.groupValues[1])
            val resolved = keys.mapNotNull { key ->
                citByNum[key]
                    ?: refByNum[key]
                    ?: refByKey[key.lowercase()]
            }.distinctBy { it.referenceKey }
            if (resolved.isNotEmpty()) {
                val span = ReferenceSpan(
                    references = resolved,
                    color = accentColor,
                    listener = listener
                )
                // match.range covers the whole bracket, inner spaces included
                // ("[ 5 ]"), so tapping anywhere on the bracket opens the reference.
                spannable.setSpan(
                    span,
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                found++
            }
        }
        val superscripts = spannable.getSpans(0, spannable.length, SuperscriptSpan::class.java)
        for (superscript in superscripts) {
            val start = spannable.getSpanStart(superscript)
            val end = spannable.getSpanEnd(superscript)
            if (start < 0 || end <= start) continue
            val number = spannable.subSequence(start, end).toString().trim()
            if (number.isEmpty() || !number.all { it.isDigit() }) continue
            if (spannable.getSpans(start, end, URLSpan::class.java).isNotEmpty()) continue
            if (spannable.getSpans(start, end, ReferenceSpan::class.java).isNotEmpty()) continue
            val ref = fnByNum[number]
                ?: citByNum[number]
                ?: continue
            val span = ReferenceSpan(
                references = listOf(ref),
                color = accentColor,
                listener = listener
            )
            spannable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            found++
        }
    }

    private fun referenceKeys(raw: String): List<String> {
        val keys = mutableListOf<String>()
        raw.split(',').forEach { part ->
            val range = REFERENCE_RANGE.find(part.trim())
            if (range != null) {
                val start = range.groupValues[1].toIntOrNull() ?: return@forEach
                val end = range.groupValues[2].toIntOrNull() ?: return@forEach
                if (end >= start && end - start <= MAX_RANGE_EXPANSION) {
                    for (number in start..end) keys.add(number.toString())
                }
            } else {
                REFERENCE_NUMBER.find(part)?.value?.let(keys::add)
            }
        }
        return keys
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    /**
     * Handles taps only (references, highlights, images and links).
     * Horizontal swipes are intercepted by the root ReaderGestureLayout before
     * they reach the TextView, so a horizontal gesture is never processed here
     * as a tap or a drag (no double page turn).
     */
    private val highlightTapListener = View.OnTouchListener { touched, event ->
        val view = touched as? TextView ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchDownTime = System.currentTimeMillis()
                false
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                val moved = abs(dx) > dp(14) || abs(dy) > dp(14)
                val elapsed = System.currentTimeMillis() - touchDownTime
                if (moved || elapsed > 420L) {
                    false
                } else {
                    val spannable = view.text as? Spanned ?: return@OnTouchListener false
                    val offset = view.getOffsetForPosition(event.x, event.y)
                        .coerceIn(0, (view.text.length - 1).coerceAtLeast(0))
                    val reference = referenceSpanAtPosition(view, spannable, event.x, event.y, offset)
                    if (reference != null) {
                        clearSelection()
                        listener?.onReferenceTapped(reference.references)
                        return@OnTouchListener true
                    }
                    val highlight = spannable.getSpans(
                        offset,
                        offset,
                        HighlightSpan::class.java
                    ).firstOrNull()
                    if (highlight != null) {
                        clearSelection()
                        val start = spannable.getSpanStart(highlight)
                        val end = spannable.getSpanEnd(highlight)
                        val text = if (start >= 0 && end > start) {
                            spannable.subSequence(start, end).toString()
                        } else {
                            ""
                        }
                        listener?.onHighlightTapped(highlight.id, text)
                        return@OnTouchListener true
                    }
                    // Images are checked before links so tapping a figure opens
                    // it full size. If the span has no tracked file (Html.fromHtml
                    // only creates the ImageSpan when imageFor() decoded something,
                    // so the map should always have the path), the tap falls
                    // through to link handling in case the image is wrapped in an <a>.
                    val imageSpan = imageSpanAtPosition(view, spannable, event.x, event.y, offset)
                    val imagePath = imageSpan?.source?.let(loadedImageSources::get)
                    if (imagePath != null) {
                        clearSelection()
                        listener?.onImageTapped(null, imagePath)
                        return@OnTouchListener true
                    }
                    val link = urlSpanAtPosition(view, spannable, event.x, event.y, offset)
                    if (link != null) {
                        val url = link.url
                        val linkStart = spannable.getSpanStart(link)
                        val linkEnd = spannable.getSpanEnd(link)
                        val label = if (linkStart >= 0 && linkEnd > linkStart) {
                            spannable.subSequence(linkStart, linkEnd).toString()
                        } else {
                            ""
                        }
                        if (url.startsWith("termino://")) {
                            val path = url.removePrefix("termino://")
                            val parts = path.split('/')
                            when {
                                parts.size >= 2 && parts[0] == "chapter" -> {
                                    val targetChapter = parts[1].toIntOrNull()
                                    if (targetChapter == null) {
                                        // Non-numeric index: the tap is not
                                        // silently consumed, it falls through
                                        // to the unresolved-link fallback.
                                        listener?.onLinkTapped(url)
                                    } else {
                                        clearSelection()
                                        listener?.onInternalChapterTapped(
                                            targetChapter,
                                            parts.getOrNull(2),
                                            label
                                        )
                                    }
                                }
                                parts.size >= 2 && parts[0] == "anchor" -> {
                                    val targetChapter = parts[1].toIntOrNull()
                                    val targetAnchor = parts.getOrNull(2)
                                    if (targetChapter == null) {
                                        listener?.onLinkTapped(url)
                                    } else {
                                        clearSelection()
                                        // If the reference belongs to the current chapter and is a sheet type
                                        // (note/affiliation/contact), open it in the floating sheet without jumping.
                                        val localRef = if (targetChapter == content?.chapterIndex && targetAnchor != null) {
                                            referencesByAnchor[targetAnchor.lowercase()]
                                        } else null
                                        if (localRef != null && localRef.isSheetReference()) {
                                            listener?.onReferenceTapped(listOf(localRef))
                                        } else {
                                            listener?.onInternalChapterTapped(
                                                targetChapter,
                                                targetAnchor,
                                                label
                                            )
                                        }
                                    }
                                }
                                else -> listener?.onLinkTapped(url)
                            }
                            true
                        } else {
                            val fragment = url.substringAfterLast('#', "")
                            val target = if (fragment.isBlank()) {
                                null
                            } else {
                                referencesByAnchor[fragment.lowercase()]
                                    ?: referencesByKey[fragment.lowercase()]
                                    ?: footnoteByNumber[fragment]
                                    ?: citationByNumber[fragment]
                                    ?: referencesByNumber[fragment]
                            }
                            if (target != null) {
                                listener?.onReferenceTapped(listOf(target))
                            } else if (url.startsWith("#") ||
                                (!url.startsWith("http") && url.contains("#"))
                            ) {
                                clearSelection()
                                if (!goToInternalAnchor(fragment, label)) {
                                    listener?.onLinkTapped(url)
                                }
                            } else {
                                listener?.onLinkTapped(url)
                            }
                            true
                        }
                    } else {
                        handlePlainTap(view, event)
                    }
                }
            }
            else -> false
        }
    }

    /**
     * Tap on text without a link: with an active selection it only clears it;
     * otherwise the left/right 28% turns the page and the center shows or
     * hides the UI.
     */
    private fun handlePlainTap(view: TextView, event: MotionEvent): Boolean {
        val x = event.x
        if (hasActiveSelection()) {
            clearSelection()
            return true
        }
        // After consuming UP, the selectable TextView keeps a half-finished
        // gesture (cursor and ghost mini-selection). A CANCEL resets it.
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        view.onTouchEvent(cancel)
        cancel.recycle()
        (view.text as? Spannable)?.let { Selection.removeSelection(it) }
        val width = view.width.coerceAtLeast(1)
        val zone = width * 0.28f
        val zones = listener?.tapZonesEnabled() != false
        when {
            zones && x < zone -> {
                listener?.onUserPageTurn()
                previousPage()
            }
            zones && x > width - zone -> {
                listener?.onUserPageTurn()
                nextPage()
            }
            else -> listener?.onTap()
        }
        return true
    }

    /**
     * Finds [needle] in the chapter (ignoring case and whitespace
     * differences), scrolls to it and briefly highlights it.
     */
    fun findAndReveal(needle: String): Boolean {
        val view = textView ?: return false
        val text = view.text ?: return false
        val wanted = needle.replace(NBSP_REGEX, " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false
        val pattern = Regex(wanted.joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        val match = pattern.find(text.toString().replace(NBSP_REGEX, " ")) ?: return false
        val spannable = text as? Spannable
        val flash = android.text.style.BackgroundColorSpan(0x66F2C14E)
        if (spannable != null) {
            spannable.setSpan(flash, match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            handler.postDelayed({
                (textView?.text as? Spannable)?.removeSpan(flash)
            }, 1800)
        }
        return scrollToOffset(match.range.first)
    }

    private fun scheduleReport() {
        handler.removeCallbacks(reportRunnable)
        handler.postDelayed(reportRunnable, 120)
    }

    /**
     * Looks for a reference at [offset] and, if there is none, at the adjacent
     * characters, so tapping the bracket or the inner space of "[ 5 ]" still opens it.
     */
    private fun referenceSpanAt(spannable: Spanned, offset: Int): ReferenceSpan? {
        val direct = spannable.getSpans(offset, offset, ReferenceSpan::class.java).firstOrNull()
        if (direct != null) return direct
        // Radius widened to 8 characters: getOffsetForPosition can drift
        // several characters (justified text, short spans such as "[5]"), and
        // getSpans uses a strict inequality (spanEnd > queryStart), so a delta
        // that is too small misses taps that land just after the bracket.
        for (delta in 1..8) {
            val before = (offset - delta).coerceAtLeast(0)
            val after = (offset + delta).coerceAtMost(spannable.length)
            if (after > before) {
                val near = spannable.getSpans(before, after, ReferenceSpan::class.java).firstOrNull()
                if (near != null) return near
            }
        }
        return null
    }

    /**
     * Reference hit-test based on the span's visual bounds, not only the
     * character offset. Justified text, a `[5]` at the end of a line or a
     * finger on a small target can shift the offset returned by
     * [TextView.getOffsetForPosition] several characters away from where the
     * user sees the citation. This walks the ReferenceSpans of the tapped line
     * and checks whether the tap falls inside the span rectangle (with a
     * margin). If neither previous method finds the span, it returns the
     * ReferenceSpan closest to the tapped offset as long as it is within 8
     * characters.
     */
    private fun referenceSpanAtPosition(
        view: TextView,
        spannable: Spanned,
        x: Float,
        y: Float,
        offset: Int
    ): ReferenceSpan? {
        val byOffset = referenceSpanAt(spannable, offset)
        if (byOffset != null) return byOffset

        val layout = view.layout ?: return null
        val line = layout.getLineForVertical((y - view.totalPaddingTop).toInt())
        if (line < 0 || line >= layout.lineCount) return null
        val padding = dp(18).toFloat()
        val localX = x - view.totalPaddingLeft
        val spans = spannable.getSpans(0, spannable.length, ReferenceSpan::class.java)
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val startLine = layout.getLineForOffset(start)
            val endLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
            if (line < startLine || line > endLine) continue
            val left = layout.getPrimaryHorizontal(start)
            val right = when {
                // Single-line span: from the span start to the end of its
                // last character.
                endLine == startLine -> layout.getPrimaryHorizontal(end)
                // Last line of a multi-line span: the span ends where its text
                // ends, not at the end of the line; getLineRight would
                // over-extend the hit-test to the rest of the line.
                line == endLine && end < spannable.length ->
                    layout.getPrimaryHorizontal(end)
                // First and middle lines (the span continues): they take the
                // full line. Also covers end == spannable.length, where
                // getPrimaryHorizontal(end) would fall outside the text.
                else -> layout.getLineRight(line).toFloat()
            }
            if (line == startLine && localX < left - padding) continue
            if (line == endLine && localX > right + padding) continue
            if (localX >= left - padding || line > startLine) {
                if (localX <= right + padding || line < endLine) {
                    return span
                }
            }
        }

        // 3. Fallback: closest span within 8 characters. Even if the offset
        // returned by getOffsetForPosition drifts several characters from the
        // actual bracket (and therefore from the rectangle computed above),
        // the reference closest to the tap is still the intended one. The
        // 8-character limit avoids triggering references elsewhere in the
        // text or stealing taps from adjacent links.
        val allSpans = spannable.getSpans(0, spannable.length, ReferenceSpan::class.java)
        var best: ReferenceSpan? = null
        var bestDistance = Int.MAX_VALUE
        for (span in allSpans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val distance = when {
                offset < start -> start - offset
                offset >= end -> offset - end + 1
                else -> 0
            }
            if (distance <= 8 && distance < bestDistance) {
                best = span
                bestDistance = distance
            }
        }
        if (best != null) return best
        return null
    }

    /**
     * Looks for a URLSpan at [offset] and, if there is none, at the adjacent
     * characters. Essential for single-character superscripts (e.g. footnote
     * "1"), where a finger tap almost always lands on the adjacent character.
     */
    private fun urlSpanAt(spannable: Spanned, offset: Int): URLSpan? {
        val direct = spannable.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
        if (direct != null) return direct
        for (delta in 1..8) {
            val before = (offset - delta).coerceAtLeast(0)
            val after = (offset + delta).coerceAtMost(spannable.length)
            if (after > before) {
                val near = spannable.getSpans(before, after, URLSpan::class.java).firstOrNull()
                if (near != null) return near
            }
        }
        return null
    }

    /**
     * URLSpan hit-test based on visual bounds with a generous touch tolerance
     * (24dp), so tapping tiny links such as note calls ("1", "¹") or
     * citations does not miss.
     */
    private fun urlSpanAtPosition(
        view: TextView,
        spannable: Spanned,
        x: Float,
        y: Float,
        offset: Int
    ): URLSpan? {
        val byOffset = urlSpanAt(spannable, offset)
        if (byOffset != null) return byOffset

        val layout = view.layout ?: return null
        val line = layout.getLineForVertical((y - view.totalPaddingTop).toInt())
        if (line < 0 || line >= layout.lineCount) return null
        val padding = dp(24).toFloat()
        val localX = x - view.totalPaddingLeft
        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val startLine = layout.getLineForOffset(start)
            val endLine = layout.getLineForOffset((end - 1).coerceAtLeast(start))
            if (line < startLine || line > endLine) continue
            val left = layout.getPrimaryHorizontal(start)
            val right = when {
                endLine == startLine -> layout.getPrimaryHorizontal(end)
                line == endLine && end < spannable.length -> layout.getPrimaryHorizontal(end)
                else -> layout.getLineRight(line).toFloat()
            }
            if (line == startLine && localX < left - padding) continue
            if (line == endLine && localX > right + padding) continue
            if (localX >= left - padding || line > startLine) {
                if (localX <= right + padding || line < endLine) {
                    return span
                }
            }
        }

        var best: URLSpan? = null
        var bestDistance = Int.MAX_VALUE
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val distance = when {
                offset < start -> start - offset
                offset >= end -> offset - end + 1
                else -> 0
            }
            if (distance <= 8 && distance < bestDistance) {
                best = span
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Image hit-test: looks for an ImageSpan at the tapped offset and, if none
     * appears, checks whether the tap falls inside the drawn rectangle of an
     * image in the text. An image character takes a single position and
     * getSpans with a point (start == end) misses one-character spans because
     * of the strict inequality, so a ±1 range is queried, backed by the
     * drawable's rectangle on its line.
     */
    private fun imageSpanAtPosition(
        view: TextView,
        spannable: Spanned,
        x: Float,
        y: Float,
        offset: Int
    ): ImageSpan? {
        val from = (offset - 1).coerceAtLeast(0)
        val to = (offset + 1).coerceAtMost(spannable.length)
        if (to > from) {
            spannable.getSpans(from, to, ImageSpan::class.java).firstOrNull()?.let { return it }
        }
        val layout = view.layout ?: return null
        val localX = x - view.totalPaddingLeft
        val localY = y - view.totalPaddingTop
        val padding = dp(6).toFloat()
        val spans = spannable.getSpans(0, spannable.length, ImageSpan::class.java)
        for (span in spans) {
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (start < 0 || end <= start) continue
            val line = layout.getLineForOffset(start)
            if (line < 0 || line >= layout.lineCount) continue
            val left = layout.getPrimaryHorizontal(start)
            val right = if (end < spannable.length) {
                // Right edge of the image character: the drawable takes exactly
                // that width on the line.
                layout.getPrimaryHorizontal(end)
            } else {
                // The image is the last character: the position after the span
                // does not exist, so measure from the drawable's edge.
                left + span.drawable.bounds.width().toFloat()
            }
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()
            if (localX >= left - padding && localX <= right + padding &&
                localY >= top - padding && localY <= bottom + padding
            ) {
                return span
            }
        }
        return null
    }

    private val reportRunnable = Runnable { reportPage() }

    private fun reportPage(force: Boolean = false, ready: Boolean = false) {
        val view = textView ?: return
        val scroll = scrollView ?: return
        val viewport = viewportHeight()
        if (viewport <= 0 || view.height <= 0) return
        val total = totalPages()
        // Rounding: jumps snap to lines and advance slightly less than a viewport.
        val page = ((scroll.scrollY + viewport / 2) / viewport).coerceIn(0, total - 1)
        val visible = visibleText()
        if (ready) {
            lastPage = page
            lastTotal = total
            listener?.onReady(total, page, visible)
        } else if (force || page != lastPage || total != lastTotal) {
            lastPage = page
            lastTotal = total
            listener?.onPageChanged(page, total, visible)
        }
    }

    private fun totalPages(): Int {
        val view = textView ?: return 1
        val viewport = viewportHeight()
        if (viewport <= 0) return 1
        return ceil(view.height.toFloat() / viewport.toFloat()).toInt().coerceAtLeast(1)
    }

    private fun viewportHeight(): Int {
        val scroll = scrollView ?: return 0
        return (scroll.height - scroll.paddingTop - scroll.paddingBottom).coerceAtLeast(1)
    }

    private fun visibleText(): String {
        val view = textView ?: return ""
        val scroll = scrollView ?: return ""
        val layout = view.layout ?: return ""
        val viewport = viewportHeight()
        val top = (scroll.scrollY - view.top - view.totalPaddingTop).coerceIn(0, layout.height)
        val bottom = (top + viewport).coerceIn(0, layout.height)
        val startLine = layout.getLineForVertical(top)
        val endLine = layout.getLineForVertical(bottom)
        val start = layout.getLineStart(startLine).coerceAtLeast(0)
        val end = layout.getLineEnd(endLine).coerceAtMost(view.text.length)
        if (end <= start) return ""
        return view.text.subSequence(start, end).toString()
            .replace(Regex("[\\s\\u00A0\\u2007\\u202F]+"), " ")
            .trim()
            .take(9000)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

private class HighlightSpan(
    val id: String,
    val colorKey: String,
    var color: Int,
    private val listener: ReaderInteractionListener?
) : ClickableSpan() {
    override fun onClick(widget: View) {
        val view = widget as? TextView ?: return
        val spannable = view.text as? Spanned ?: return
        val start = spannable.getSpanStart(this)
        val end = spannable.getSpanEnd(this)
        val text = if (start >= 0 && end > start) {
            spannable.subSequence(start, end).toString()
        } else {
            ""
        }
        listener?.onHighlightTapped(id, text)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.bgColor = color
        ds.isUnderlineText = false
    }
}


private class ReferenceSpan(
    val references: List<ReaderReference>,
    var color: Int,
    private val listener: ReaderInteractionListener?
) : ClickableSpan() {
    override fun onClick(widget: View) {
        listener?.onReferenceTapped(references)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = false
    }
}

private data class PreparedRender(
    val builder: SpannableStringBuilder,
    val anchors: Map<String, Int>,
    val referencesByKey: Map<String, ReaderReference>,
    val referencesByAnchor: Map<String, ReaderReference>,
    val footnoteByNumber: Map<String, ReaderReference>,
    val citationByNumber: Map<String, ReaderReference>,
    val referencesByNumber: Map<String, ReaderReference>,
    val highlightSpans: Map<String, HighlightSpan>,
    val imageSources: Map<String, String>
)
