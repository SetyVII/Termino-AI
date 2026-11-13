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
import android.util.Log
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
            lower.contains("citation") ||
            label.startsWith("Afiliación", ignoreCase = true) ||
            label.startsWith("Contacto", ignoreCase = true) ||
            label.startsWith("Nota", ignoreCase = true) ||
            label.startsWith("Bibliografía", ignoreCase = true)
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
    /** Si los toques en los bordes deben pasar página. */
    fun tapZonesEnabled(): Boolean = true
    /** Paso de página con gesto (toque en borde, deslizar o volumen). */
    fun onUserPageTurn() {}
    /** Tamaño de letra provisional mientras se pellizca (null al terminar). */
    fun onFontSizePreview(size: Int?) {}
    /** Pellizco terminado: nuevo tamaño de letra. */
    fun onFontSizeGesture(size: Int) {}
    fun onLinkTapped(url: String)
    fun onDebug(message: String)
}

private data class ReaderStyle(
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.65f,
    val font: ReaderFont = ReaderFont.SERIF
)

/**
 * Motor de lectura nativo: pinta HTML de un capítulo en un TextView con
 * Spannable (subrayados, referencias, imágenes, enlaces internos), pagina por
 * viewport y notifica todo a través de [ReaderInteractionListener].
 *
 * @param assetDir carpeta donde están las imágenes del libro (las rutas
 * relativas del HTML se resuelven contra ella).
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

        // "[5]", "[ 5 ]", "[13, 18]", "[3-5]", "[ 7 – 9 ]": espacios opcionales dentro de los corchetes.
        private val REFERENCE_PATTERN = Regex("\\[\\s*(\\d+(?:\\s*[,;–-]\\s*\\d+)*)\\s*]")
        private val REFERENCE_RANGE = Regex("(\\d+)\\s*[-,–]\\s*(\\d+)")
        private val REFERENCE_NUMBER = Regex("\\d+")
        // Los EPUB técnicos usan NBSP en los números de sección ("11.2\u00A0").
        // Se normaliza a espacio (1:1, sin cambiar offsets) antes de buscar.
        private val NBSP_REGEX = Regex("[\\u00A0\\u2007\\u202F]")
        private const val MAX_RANGE_EXPANSION = 12
        private const val PAGE_OUT_MS = 140L
        private const val PAGE_IN_MS = 180L
        private val ANCHOR_TARGET_TAG_REGEX = Regex(
            """(<([a-zA-Z0-9]+)(?:\s+[^>]*)?\sid\s*=\s*["']([^"']+)["'][^>]*>)"""
        )
    }

    var listener: ReaderInteractionListener? = null

    /** Resuelve la fuente elegida (sistema o importada) a un Typeface. */
    var typefaceResolver: ((ReaderFont) -> Typeface)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var textView: TextView? = null
    private var scrollView: NestedScrollView? = null
    private var content: ReaderContent? = null
    private var style = ReaderStyle()
    private val highlightSpans = LinkedHashMap<String, HighlightSpan>()
    // Imágenes del capítulo actual: src original del <img> -> ruta absoluta
    // del archivo decodificado por imageFor(). Permite resolver el toque en
    // un ImageSpan a la ruta del archivo original (sin extraer el bitmap del
    // drawable en runtime, que perdería calidad).
    private val loadedImageSources = mutableMapOf<String, String>()
    private val imageCache = LruCache<String, Bitmap>(24)
    private var referencesByKey: Map<String, ReaderReference> = emptyMap()
    private var referencesByNumber: Map<String, ReaderReference> = emptyMap()
    // En un mismo libro pueden coexistir notas al pie ("Fn1") y bibliografía
    // ("CR1") con el mismo número. Los superíndices deben resolver a la nota
    // al pie y los corchetes [1] a la bibliografía.
    private var footnoteByNumber: Map<String, ReaderReference> = emptyMap()
    private var citationByNumber: Map<String, ReaderReference> = emptyMap()
    private var referencesByAnchor: Map<String, ReaderReference> = emptyMap()
    // Offsets de caracteres exactos para cada elemento con id en el capítulo,
    // extraídos de los anclas inyectados. Permite saltar a cualquier ancla en O(1)
    // sin depender de coincidencias heurísticas de texto.
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
            // El usuario puede arrastrar los tiradores hasta un espacio o un salto de línea:
            // recortamos los extremos para que el subrayado y el texto enviado a la IA no
            // incluyan espacios sobrantes. Si solo había espacios, caemos al ajuste por palabra.
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
            val selected = if (view != null && start >= 0 && end > start) {
                view.text.subSequence(start, end).toString()
            } else {
                ""
            }
            Log.d("LectorReader", "ActionMode start sel=$start-$end text=$selected highlight=${view?.highlightColor}")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            val view = textView
            if (view != null) normalizeSelection(view)
            val start = view?.selectionStart ?: -1
            val end = view?.selectionEnd ?: -1
            val selected = if (view != null && start >= 0 && end > start) {
                view.text.subSequence(start, end).toString()
            } else {
                ""
            }
            Log.d("LectorReader", "ActionMode prepare sel=$start-$end text=$selected")
            menu.clear()
            val highlightMenu = menu.addSubMenu(Menu.NONE, 0, 0, "Resaltar")
            // En pantallas estrechas (~411dp) el disparador "Resaltar" caía al
            // overflow (⋮): el popup anidado de colores se recortaba contra el
            // borde y se perdía el último ítem ("Morado"). Como icono con
            // SHOW_AS_ACTION_ALWAYS vive directamente en la barra de selección,
            // donde el popup de los 5 colores ancla con espacio de sobra.
            highlightMenu.item?.apply {
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                setIcon(android.R.drawable.ic_menu_more)
            }
            highlightMenu.add(Menu.NONE, ACTION_YELLOW, 0, "Amarillo")
            highlightMenu.add(Menu.NONE, ACTION_GREEN, 1, "Verde")
            highlightMenu.add(Menu.NONE, ACTION_BLUE, 2, "Azul")
            highlightMenu.add(Menu.NONE, ACTION_PINK, 3, "Rosa")
            highlightMenu.add(Menu.NONE, ACTION_PURPLE, 4, "Morado")
            menu.add(Menu.NONE, ACTION_DEFINE, 1, "Definir")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_EXPLAIN, 2, "Explicar")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add(Menu.NONE, ACTION_ASK, 3, "Preguntar")
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
        // Se recuerda el primer carácter visible (no los píxeles): al cambiar
        // el tamaño o la fuente el texto se recoloca y seguimos en la misma frase.
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

    /** Vista previa inmediata: se escala la vista (barato) y se muestra el tamaño. */
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
     * True si el TextView tiene una selección de texto activa. El
     * ReaderGestureLayout lo usa para no robar el gesto cuando el usuario
     * está arrastrando los tiradores de la selección.
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
        // La línea cortada por el borde inferior pasa a ser la primera de la
        // página siguiente: ninguna línea se ve partida ni se salta.
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
     * Ajusta [y] (coordenada de scroll) al inicio de una línea. Con
     * [preferNext] se elige la siguiente línea si [y] cae a mitad de una,
     * para que la página no empiece con una línea cortada.
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
        // Margen superior pequeño para que la línea no quede pegada al borde.
        return (snapped + top - dp(6)).coerceAtLeast(0)
    }

    private fun turnPage(direction: Int, targetY: Int) {
        val scroll = scrollView ?: return
        if (viewportHeight() <= 0) return
        scroll.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        // Cancela cualquier turno anterior (incluido su withEndAction) y el fade de render().
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
        // SIMPLE + NONE: renderizado instantáneo en O(N) sin cálculo combinatorio de Knuth-Plass
        // ni búsqueda en diccionario de separación silábica por cada palabra (igual que Moon+ Reader).
        view.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        view.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        view.justificationMode = Layout.JUSTIFICATION_MODE_INTER_WORD
        view.setPadding(dp(28), dp(22), dp(28), dp(72))
        scroll.isFillViewport = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleReport() }
        // Los swipes horizontales los intercepta el ReaderGestureLayout raíz
        // (ver NativeReaderView); aquí el TextView solo atiende taps sobre
        // referencias, subrayados, imágenes y enlaces.
        view.setOnTouchListener { touched, event ->
            highlightTapListener.onTouch(touched, event)
        }
        applyTypography()
    }

    private fun injectAnchorTargets(html: String): String {
        // Desanidar cualquier target-anchor que haya quedado dentro de un <a> por parseos anteriores
        // (en HTML un <a> dentro de otro <a> cierra prematuramente el externo, eliminando su URLSpan).
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
                    // Nunca anidar un <a> dentro de otro <a>: se inserta como hermano anterior
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
     * Decodifica la imagen referenciada por [source] y la escala al ancho de
     * la columna del lector (el ancho medido del TextView; antes del primer
     * layout se usa el 55% de la pantalla como fallback para que la imagen
     * quepa incluso con el asistente de IA acoplado). Además registra en
     * [loadedImageSources] la ruta del archivo que respalda cada src, para
     * que un toque sobre el ImageSpan pueda abrir la imagen a tamaño
     * completo sin reextraerla del drawable.
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
        // El ancho disponible es el de la columna del lector (el TextView ya
        // medido), no el de la pantalla completa: con el asistente de IA
        // acoplado el panel mide bastante menos que la pantalla (p. ej.
        // ~1006px frente a 1600px) y usar widthPixels recortaba las imágenes
        // fuera de la columna. Si el TextView aún no está medido (primer
        // render, antes del primer layout), el 55% de la pantalla garantiza
        // que la imagen quepa incluso con el panel acoplado.
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
     * Resuelve un enlace interno (`#Aff8`, `#Sec5`, `#Fn1`, `#CR8`...).
     *
     * - Si el anchor corresponde a una referencia que se muestra en la hoja
     *   (nota, cita, afiliación, contacto), abre la hoja. Esto evita que el
     *   superíndice "(1)" de un autor caiga en la nota al pie "1".
     * - Si es una sección/figura/tabla/bibliografía, hace scroll al texto
     *   del encabezado usando el contenido semántico extraído del EPUB
     *   (normalizando NBSP), no solo la etiqueta visible del enlace.
     * - Si el anchor parece una referencia pero no se encontró, devuelve
     *   false para que la UI muestre un aviso en vez de buscar un "1" suelto.
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

        // Salto directo exacto por AnchorSpan en O(1)
        val exactOffset = anchorOffsets[cleanFragment]
        if (exactOffset != null) {
            Log.d("LectorReader", "goToInternalAnchor: exact offset for '$cleanFragment' at $exactOffset")
            return scrollToOffset(exactOffset)
        }

        // Fallback a búsqueda semántica de texto
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
     * Busca el texto de una sección/figura/tabla en el capítulo renderizado.
     * Primero prueba el contenido semántico completo ("6.2 Optimization
     * Settings") y, si no aparece, el número de encabezado al inicio de una
     * línea ("6.2"). El NBSP se normaliza a espacio sin alterar offsets.
     */
    private fun scrollToText(primary: String, fallback: String): Boolean {
        val view = textView ?: return false
        view.layout ?: return false
        val haystack = view.text.toString().replace(NBSP_REGEX, " ")
        val needles = buildList {
            add(primary)
            // La leyenda de una tabla/figura puede incluir una cita al final
            // que no coincide exactamente con el texto renderizado; probar su
            // prefijo ("Table 3.1 ...") evita caer en la sección 3.1.
            val prefix = primary.take(60).trim()
            if (prefix.length >= 12 && prefix != primary) add(prefix)
            // Etiqueta corta ("Table 3.1", "Figure 6.1"): sobrevive a los
            // saltos de línea que Html.fromHtml inserta en leyendas largas.
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
        // Salto directo: smoothScrollTo sobre un contenido de decenas de miles
        // de píxeles no completaba el recorrido en este NestedScrollView y el
        // lector se quedaba en la página de origen. scrollTo es el mismo
        // mecanismo que usa la paginación y actualiza el informe de página.
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
                // match.range cubre el corchete completo, incluidos los espacios internos
                // ("[ 5 ]"), para que el toque en cualquier punto del corchete abra la referencia.
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
     * Solo atiende taps (referencias, subrayados, imágenes y enlaces). Los
     * swipes horizontales los intercepta el ReaderGestureLayout raíz antes de
     * que lleguen al TextView, así que un gesto horizontal nunca se procesa
     * aquí ni como tap ni como arrastre (no hay doble giro de página).
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
                    Log.d("LectorReader", "tap offset=$offset refs=${spannable.getSpans(offset, offset, ReferenceSpan::class.java).size} refNearby=${reference != null} urls=${spannable.getSpans(offset, offset, URLSpan::class.java).size} url=${spannable.getSpans(offset, offset, URLSpan::class.java).firstOrNull()?.url} sel=${view.selectionStart}-${view.selectionEnd}")
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
                    // Imágenes: se comprueban antes que los enlaces para que
                    // tocar una figura la abra a tamaño completo. Si el span no
                    // tiene archivo rastreado (Html.fromHtml solo crea el
                    // ImageSpan cuando imageFor() decodificó algo, así que el
                    // mapa siempre debería tener la ruta), el toque cae al
                    // manejo de enlaces por si la imagen va envuelta en un <a>.
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
                                        // Índice no numérico: el toque no se
                                        // consume en silencio, cae al fallback
                                        // de enlace no resuelto.
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
                                        // Si la referencia pertenece al capítulo actual y es de tipo hoja (nota/afiliación/contacto),
                                        // la abrimos directamente en la hoja flotante sin salto de página.
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
     * Toque sobre texto sin enlace: con selección activa solo la limpia; si no,
     * el 28 % izquierdo/derecho pasa página (como Moon+ Reader o Play Libros)
     * y el centro muestra u oculta la interfaz.
     */
    private fun handlePlainTap(view: TextView, event: MotionEvent): Boolean {
        val x = event.x
        if (hasActiveSelection()) {
            clearSelection()
            return true
        }
        // Al consumir el UP, el TextView seleccionable se queda con el gesto
        // a medias (cursor y mini-selección fantasma). Un CANCEL lo reinicia.
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
     * Localiza [needle] en el capítulo (ignorando mayúsculas y diferencias de
     * espacios), lleva la vista hasta él y lo resalta un instante.
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
     * Busca una referencia en [offset] y, si no hay, en los caracteres contiguos.
     * Así tocar el corchete o el espacio interior de "[ 5 ]" sigue abriendo la referencia.
     */
    private fun referenceSpanAt(spannable: Spanned, offset: Int): ReferenceSpan? {
        val direct = spannable.getSpans(offset, offset, ReferenceSpan::class.java).firstOrNull()
        if (direct != null) return direct
        // Radio ampliado a 8 caracteres: getOffsetForPosition puede desviarse
        // varios caracteres (texto justificado, spans cortos como "[5]"), y
        // getSpans usa desigualdad estricta (spanEnd > queryStart), por lo que
        // un delta demasiado pequeño deja fuera toques que caen justo después
        // del corchete.
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
     * Hit-test de referencias basado en los límites visuales del span, no solo
     * en el offset de carácter. Algunos lectores (texto justificado, `[5]` al
     * final de una línea, dedos sobre targets pequeños) pueden desplazar el
     * offset devuelto por [TextView.getOffsetForPosition] varios caracteres
     * respecto a donde el usuario ve la cita. Esta función recorre los
     * ReferenceSpan de la línea tocada y comprueba si el toque cae dentro del
     * rectángulo del span (con margen). Si ninguno de los dos métodos
     * anteriores encuentra el span, devuelve el ReferenceSpan más cercano al
     * offset tocado siempre que esté a 8 caracteres o menos.
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
                // Span de una sola línea: del inicio del span al final de su
                // último carácter.
                endLine == startLine -> layout.getPrimaryHorizontal(end)
                // Última línea de un span multilínea: el span termina donde
                // acaba su texto, no al final de la línea; getLineRight
                // sobre-extendería el hit-test al resto de la línea.
                line == endLine && end < spannable.length ->
                    layout.getPrimaryHorizontal(end)
                // Primeras e intermedias líneas (el span continúa): ocupan la
                // línea completa. También cubre end == spannable.length, donde
                // getPrimaryHorizontal(end) se saldría del texto.
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

        // 3. Fallback: span más cercano dentro de 8 caracteres. Aunque el
        // offset devuelto por getOffsetForPosition se desvíe varios caracteres
        // del corchete real (y por tanto del rectángulo calculado arriba),
        // la referencia más próxima al toque sigue siendo la pretendida. El
        // límite de 8 caracteres evita activar referencias de otra parte del
        // texto o robar taps de enlaces adyacentes.
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
     * Busca un URLSpan en [offset] y, si no hay, en los caracteres contiguos.
     * Esencial para superíndices de un solo carácter (p. ej. notas al pie "1"),
     * donde el toque con el dedo casi siempre aterriza en el carácter adyacente.
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
     * Hit-test de URLSpan basado en límites visuales con tolerancia táctil generosa (24dp),
     * emulando el comportamiento maduro de Moon+ Reader para que tocar enlaces diminutos
     * como llamadas a nota ("1", "¹") o citas nunca falle con el dedo.
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
     * Hit-test de imágenes: busca un ImageSpan en el offset tocado y, si no
     * aparece, comprueba si el toque cae dentro del rectángulo dibujado de
     * alguna imagen del texto. El carácter de una imagen ocupa una sola
     * posición y getSpans con un punto (inicio == fin) no alcanza spans de un
     * solo carácter por la desigualdad estricta, por eso se consulta un rango
     * de ±1 y se respalda con el rectángulo del drawable en su línea.
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
                // Borde derecho del carácter de la imagen: el drawable ocupa
                // exactamente ese ancho en la línea.
                layout.getPrimaryHorizontal(end)
            } else {
                // La imagen es el último carácter: la posición tras el span no
                // existe, así que medimos desde el borde del drawable.
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
        // Redondeo: los saltos se ajustan a líneas y avanzan algo menos de un viewport.
        val page = ((scroll.scrollY + viewport / 2) / viewport).coerceIn(0, total - 1)
        val visible = visibleText()
        if (ready) {
            lastPage = page
            lastTotal = total
            listener?.onReady(total, page, visible)
            logReferencePositions()
        } else if (force || page != lastPage || total != lastTotal) {
            lastPage = page
            lastTotal = total
            listener?.onPageChanged(page, total, visible)
            logReferencePositions()
        }
    }

    private fun logReferencePositions() {
        val view = textView ?: return
        val layout = view.layout ?: return
        val text = view.text as? Spanned ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val spans = text.getSpans(0, text.length, ReferenceSpan::class.java)
        spans.take(12).forEach { span ->
            val start = text.getSpanStart(span)
            if (start < 0) return@forEach
            val line = layout.getLineForOffset(start)
            val x = location[0] + view.totalPaddingLeft +
                layout.getPrimaryHorizontal(start).toInt()
            val y = location[1] + view.totalPaddingTop + layout.getLineTop(line)
            Log.d(
                "LectorReader",
                "refpos ${span.references.map { it.referenceKey }} x=$x y=$y start=$start"
            )
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
