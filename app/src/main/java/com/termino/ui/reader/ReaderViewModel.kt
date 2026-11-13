package com.termino.ui.reader

import com.termino.core.ai.AiStreamEvent
import com.termino.core.parser.EpubProcessor
import com.termino.core.parser.TocItem
import com.termino.core.rag.HybridRagEngine
import com.termino.core.rag.RagSearchResult
import com.termino.core.reader.HighlightColor
import com.termino.core.reader.NativeReaderController
import com.termino.core.reader.ReaderContent
import com.termino.core.reader.ReaderFont
import com.termino.core.reader.ReaderInteractionListener
import com.termino.core.reader.ReaderReference
import com.termino.core.reader.ReaderTheme
import com.termino.core.reader.isSheetAnchor
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.termino.TerminoApp
import com.termino.ai.AiRepository
import com.termino.ai.ReadingContextBuilder
import com.termino.ai.SourceRef
import com.termino.data.db.ChapterDao
import com.termino.data.db.ReferenceDao
import com.termino.data.db.ChapterSummary
import com.termino.data.db.ChatMessageEntity
import com.termino.data.db.HighlightEntity
import com.termino.data.model.AiPaneMode
import com.termino.data.model.AiTab
import com.termino.data.model.QuickActionType
import com.termino.data.prefs.AppSettings
import com.termino.data.prefs.SettingsRepository
import com.termino.data.repo.BookRepository
import com.termino.data.repo.ChatRepository
import com.termino.data.repo.HighlightRepository
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(
    private val appContext: Context,
    private val bookId: String,
    private val bookRepository: BookRepository,
    private val chapterDao: ChapterDao,
    private val referenceDao: ReferenceDao,
    private val bookmarkDao: com.termino.data.db.BookmarkDao,
    private val fontRepository: com.termino.data.fonts.FontRepository,
    private val highlightRepository: HighlightRepository,
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository,
    private val aiRepository: AiRepository,
    private val ragEngine: HybridRagEngine,
    private val contextBuilder: ReadingContextBuilder,
    private val epubProcessor: EpubProcessor,
    val controller: NativeReaderController
) : ViewModel(), ReaderInteractionListener {

    private val _ui = MutableStateFlow(ReaderUiState())
    val ui: StateFlow<ReaderUiState> = _ui.asStateFlow()

    private val _content = MutableStateFlow<ReaderContent?>(null)
    val content: StateFlow<ReaderContent?> = _content.asStateFlow()

    private var latestSettings = AppSettings()
    private var references: List<ReaderReference> = emptyList()
    private var settingsInitialized = false
    private var requestId = 0L
    private var pendingAnchorFragment: String? = null
    private var pendingAnchorLabel: String? = null
    private var pendingAnchorAllowSheet: Boolean = true
    private var pendingTargetScrollY: Int? = null
    /** Texto a localizar tras cargar un capítulo (búsqueda o marcador). */
    private var pendingFindText: String? = null
    private var searchJob: Job? = null

    data class ReaderNavEntry(
        val chapterIndex: Int,
        val pageIndex: Int,
        val scrollY: Int
    )

    private val navStack = ArrayDeque<ReaderNavEntry>()

    fun pushNavPosition() {
        val state = _ui.value
        val scrollY = controller.currentScrollY()
        navStack.addLast(
            ReaderNavEntry(
                chapterIndex = state.currentChapterIndex,
                pageIndex = state.pageIndex,
                scrollY = scrollY
            )
        )
        if (navStack.size > 50) navStack.removeFirst()
        _ui.update { it.copy(canNavigateBack = true) }
    }

    fun navigateBack(): Boolean {
        val entry = navStack.removeLastOrNull() ?: return false
        _ui.update { it.copy(canNavigateBack = navStack.isNotEmpty()) }
        val state = _ui.value
        if (entry.chapterIndex == state.currentChapterIndex) {
            controller.scrollToY(entry.scrollY)
        } else {
            loadChapter(
                chapterIndex = entry.chapterIndex,
                initialPage = entry.pageIndex,
                targetScrollY = entry.scrollY
            )
        }
        return true
    }
    private var chatJob: Job? = null
    private var quickJob: Job? = null
    private var progressJob: Job? = null
    private var suppressPartialSave = false
    // Momento en que el usuario cerró la última hoja de referencias. Sirve para
    // no abrir una hoja nueva mientras el ModalBottomSheet anterior aún está
    // animando su cierre: si el toque llega antes de que la animación termine,
    // la hoja nueva se encola y se muestra al terminar.
    private var lastReferenceDismissAt = 0L
    private var pendingReferenceOpen: Job? = null

    /** Modo oscuro efectivo de la app; lo informa la pantalla para resolver AUTO. */
    private var appDark = false

    init {
        controller.listener = this
        controller.typefaceResolver = fontRepository::typeface
        viewModelScope.launch {
            fontRepository.fonts.collect { list -> _ui.update { it.copy(fonts = list) } }
        }
        observeSettings()
        observeHighlights()
        observeChat()
        observeBookmarks()
        loadBook()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                latestSettings = settings
                val previous = _ui.value
                val resolved = settings.readerTheme.resolve(appDark)
                val styleChanged = previous.theme != resolved ||
                    previous.fontSize != settings.fontSize ||
                    previous.lineHeight != settings.lineHeight ||
                    previous.font != settings.readerFont
                _ui.update { current ->
                    current.copy(
                        theme = resolved,
                        themeChoice = settings.readerTheme,
                        fontSize = settings.fontSize,
                        lineHeight = settings.lineHeight,
                        font = settings.readerFont,
                        eyeCare = settings.eyeCare,
                        tapZones = settings.tapZones,
                        volumeKeysTurnPages = settings.volumeKeysTurnPages,
                        keepScreenOn = settings.keepScreenOn,
                        pdfOriginalPages = settings.pdfOriginalPages,
                        paneMode = when {
                            settingsInitialized -> current.paneMode
                            compactLayout -> AiPaneMode.MINIMIZED
                            else -> settings.aiPaneMode
                        },
                        paneFraction = if (settingsInitialized) current.paneFraction else settings.aiPaneFraction
                    )
                }
                settingsInitialized = true
                if (styleChanged) {
                    controller.updateStyle(
                        resolved,
                        settings.fontSize,
                        settings.lineHeight,
                        settings.readerFont
                    )
                }
            }
        }
    }

    private fun observeHighlights() {
        viewModelScope.launch {
            highlightRepository.observeForBook(bookId).collect { highlights ->
                _ui.update { state ->
                    state.copy(
                        allHighlights = highlights,
                        highlights = highlights.filter {
                            it.chapterIndex == state.currentChapterIndex
                        }
                    )
                }
            }
        }
    }

    private fun observeChat() {
        viewModelScope.launch {
            chatRepository.observeMessages(bookId).collect { messages ->
                _ui.update { it.copy(chatMessages = messages) }
            }
        }
    }

    private fun loadBook() {
        viewModelScope.launch {
            val bookDeferred = async(Dispatchers.IO) { bookRepository.getBook(bookId) }
            val chaptersDeferred = async(Dispatchers.IO) { chapterDao.getSummaries(bookId) }
            val referencesDeferred = async(Dispatchers.IO) {
                referenceDao.getForBook(bookId).map { reference ->
                    ReaderReference(
                        referenceKey = reference.referenceKey,
                        numericKey = reference.numericKey,
                        label = reference.label,
                        content = reference.content,
                        chapterIndex = reference.chapterIndex,
                        anchor = reference.anchor
                    )
                }
            }
            val book = bookDeferred.await()
            if (book == null) {
                _ui.update {
                    it.copy(loading = false, error = "No se encontró el libro en la biblioteca")
                }
                return@launch
            }
            val bookFile = File(book.filePath)
            val tocDeferred = async(Dispatchers.IO) {
                if (book.format.equals("EPUB", true) && bookFile.isFile) {
                    epubProcessor.extractToc(bookFile)
                } else emptyList()
            }
            val chapters = chaptersDeferred.await()
            references = referencesDeferred.await()
            val tocItems = tocDeferred.await()
            val initialChapter = book.lastChapterIndex.coerceIn(
                0,
                (chapters.size - 1).coerceAtLeast(0)
            )
            _ui.update {
                it.copy(
                    book = book,
                    chapters = chapters,
                    tocItems = tocItems,
                    loading = true,
                    progress = book.progress,
                    theme = latestSettings.readerTheme.resolve(appDark),
                    themeChoice = latestSettings.readerTheme,
                    fontSize = latestSettings.fontSize,
                    lineHeight = latestSettings.lineHeight,
                    font = latestSettings.readerFont
                )
            }
            if (book.format.equals("EPUB", true)) {
                maybeExtractReferences(book)
            }
            loadChapter(initialChapter, book.lastPageIndex)
            withContext(Dispatchers.IO) {
                bookRepository.touch(bookId)
            }
        }
    }

    private fun maybeExtractReferences(book: com.termino.data.db.BookEntity) {
        if (references.isNotEmpty()) return
        val prefs = appContext.getSharedPreferences("termino_reference_cache", Context.MODE_PRIVATE)
        if (prefs.getBoolean(book.id, false)) return
        val bookFile = File(book.filePath)
        if (!bookFile.isFile) return
        viewModelScope.launch(Dispatchers.IO) {
            val temp = File(appContext.cacheDir, "refs_${book.id}")
            val parsed = runCatching {
                FileInputStream(bookFile).use { stream ->
                    epubProcessor.parse(stream, temp)
                }
            }.getOrNull()
            val extracted = parsed?.references.orEmpty()
            if (extracted.isNotEmpty()) {
                referenceDao.insertAll(
                    extracted.map { reference ->
                        com.termino.data.db.BookReferenceEntity(
                            bookId = bookId,
                            referenceKey = reference.referenceKey,
                            numericKey = reference.numericKey,
                            label = reference.label,
                            content = reference.content,
                            chapterIndex = reference.chapterIndex,
                            anchor = reference.anchor
                        )
                    }
                )
            }
            prefs.edit().putBoolean(book.id, true).apply()
            temp.deleteRecursively()
            if (extracted.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    references = extracted.map { reference ->
                        ReaderReference(
                            referenceKey = reference.referenceKey,
                            numericKey = reference.numericKey,
                            label = reference.label,
                            content = reference.content,
                            chapterIndex = reference.chapterIndex,
                            anchor = reference.anchor
                        )
                    }
                    val state = _ui.value
                    if (state.currentChapter != null) {
                        loadChapter(state.currentChapterIndex, state.pageIndex)
                    }
                }
            }
        }
    }

    private fun loadChapter(
        chapterIndex: Int,
        initialPage: Int = 0,
        highlightId: String? = null,
        anchorFragment: String? = null,
        anchorLabel: String? = null,
        targetScrollY: Int? = null,
        allowSheet: Boolean = true
    ) {
        pendingAnchorFragment = anchorFragment
        pendingAnchorLabel = anchorLabel
        pendingAnchorAllowSheet = allowSheet
        pendingTargetScrollY = targetScrollY
        // El id se asigna fuera de la corrutina: dos llamadas rápidas a
        // loadChapter siempre producen ids crecientes y el contenido más
        // reciente es el que gana el render, sin depender del orden en que
        // cada corrutina llega a publicar en _content.
        requestId++
        val request = requestId
        viewModelScope.launch {
            val chapterDeferred = async(Dispatchers.IO) { chapterDao.getChapter(bookId, chapterIndex) }
            val highlightsDeferred = async(Dispatchers.IO) { highlightRepository.getForChapter(bookId, chapterIndex) }
            val chapter = chapterDeferred.await()
            val highlights = highlightsDeferred.await()
            if (chapter == null) {
                _ui.update {
                    it.copy(loading = false, error = "No se pudo cargar el capítulo")
                }
                return@launch
            }
            _content.value = ReaderContent(
                requestId = request,
                chapterIndex = chapterIndex,
                html = chapter.html,
                initialPage = initialPage,
                highlights = highlights.map { it.toReaderHighlight() },
                references = references
            )
            _ui.update {
                it.copy(
                    currentChapter = chapter,
                    currentChapterIndex = chapterIndex,
                    pageIndex = if (initialPage == Int.MAX_VALUE) 0 else initialPage,
                    totalPages = 1,
                    visibleText = "",
                    highlights = highlights,
                    selection = null,
                    pendingHighlightId = highlightId,
                    loading = false,
                    error = null,
                    progress = computeProgress(chapterIndex, initialPage, 1)
                )
            }
        }
    }

    override fun onReady(totalPages: Int, page: Int, visibleText: String) {
        val safeTotal = totalPages.coerceAtLeast(1)
        _ui.update {
            it.copy(
                totalPages = safeTotal,
                pageIndex = page.coerceIn(0, safeTotal - 1),
                visibleText = visibleText,
                loading = false
            )
        }
        saveProgressDebounced()
        val pending = _ui.value.pendingHighlightId
        if (pending != null) {
            controller.goToHighlight(pending)
            _ui.update { it.copy(pendingHighlightId = null) }
        }
        val findText = pendingFindText
        if (findText != null) {
            pendingFindText = null
            controller.findAndReveal(findText)
            return
        }
        val targetY = pendingTargetScrollY
        if (targetY != null) {
            pendingTargetScrollY = null
            controller.scrollToY(targetY)
        } else {
            val anchorFragment = pendingAnchorFragment
            val anchorLabel = pendingAnchorLabel
            val allowSheet = pendingAnchorAllowSheet
            if (anchorFragment != null || anchorLabel != null) {
                pendingAnchorFragment = null
                pendingAnchorLabel = null
                pendingAnchorAllowSheet = true
                controller.goToInternalAnchor(anchorFragment ?: "", anchorLabel ?: "", allowSheet)
            }
        }
    }

    override fun onPageChanged(page: Int, totalPages: Int, visibleText: String) {
        val safeTotal = totalPages.coerceAtLeast(1)
        _ui.update {
            it.copy(
                pageIndex = page.coerceIn(0, safeTotal - 1),
                totalPages = safeTotal,
                visibleText = visibleText
            )
        }
        saveProgressDebounced()
    }

    private fun saveProgressDebounced() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            delay(450)
            val state = _ui.value
            val progress = computeProgress(
                state.currentChapterIndex,
                state.pageIndex,
                state.totalPages
            )
            _ui.update { it.copy(progress = progress) }
            bookRepository.updateProgress(
                bookId = bookId,
                chapterIndex = state.currentChapterIndex,
                pageIndex = state.pageIndex,
                progress = progress
            )
        }
    }

    private fun computeProgress(chapterIndex: Int, pageIndex: Int, totalPages: Int): Float {
        val chapterCount = _ui.value.chapters.size.coerceAtLeast(1)
        val chapterFraction = if (totalPages > 1) {
            (pageIndex + 1).toFloat() / totalPages.toFloat()
        } else {
            1f
        }
        return ((chapterIndex + chapterFraction) / chapterCount).coerceIn(0f, 1f)
    }

    private fun setSelection(text: String, start: Int, end: Int) {
        _ui.update {
            it.copy(
                selection = SelectionUi(
                    text = text,
                    start = start,
                    end = end,
                    left = 0f,
                    top = 0f,
                    width = 0f,
                    height = 0f,
                    existingHighlightId = null
                )
            )
        }
    }

    override fun onSelectionCleared() {
        _ui.update { it.copy(selection = null) }
    }

    override fun onHighlightRequested(
        text: String,
        start: Int,
        end: Int,
        color: HighlightColor
    ) {
        setSelection(text, start, end)
        highlight(color)
    }

    override fun onDictionaryRequested(text: String, start: Int, end: Int) {
        setSelection(text, start, end)
        openQuickAction(QuickActionType.DICTIONARY)
    }

    override fun onExplainRequested(text: String, start: Int, end: Int) {
        setSelection(text, start, end)
        openQuickAction(QuickActionType.EXPLAIN)
    }

    override fun onAskRequested(text: String, start: Int, end: Int) {
        setSelection(text, start, end)
        askAboutSelection()
    }

    override fun onHighlightTapped(id: String, text: String) {
        val existing = _ui.value.allHighlights.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(highlightAction = existing) }
    }

    fun dismissHighlightAction() {
        _ui.update { it.copy(highlightAction = null) }
    }

    fun askHighlight() {
        val highlight = _ui.value.highlightAction ?: return
        setSelection(highlight.selectedText, highlight.startOffset, highlight.endOffset)
        _ui.update { it.copy(highlightAction = null) }
        askAboutSelection()
    }

    fun defineHighlight() {
        val highlight = _ui.value.highlightAction ?: return
        setSelection(highlight.selectedText, highlight.startOffset, highlight.endOffset)
        _ui.update { it.copy(highlightAction = null) }
        openQuickAction(QuickActionType.DICTIONARY)
    }

    fun explainHighlight() {
        val highlight = _ui.value.highlightAction ?: return
        setSelection(highlight.selectedText, highlight.startOffset, highlight.endOffset)
        _ui.update { it.copy(highlightAction = null) }
        openQuickAction(QuickActionType.EXPLAIN)
    }

    fun highlight(color: HighlightColor) {
        val state = _ui.value
        val selection = state.selection ?: return
        if (selection.existingHighlightId != null) return
        val chapter = state.currentChapter ?: return
        viewModelScope.launch {
            runCatching {
                highlightRepository.add(
                    bookId = bookId,
                    chapterIndex = chapter.chapterIndex,
                    pageLabel = chapter.pageLabel,
                    startOffset = selection.start,
                    endOffset = selection.end,
                    selectedText = selection.text,
                    color = color
                )
            }.onSuccess { entity ->
                controller.applyHighlight(entity.id, color.key, selection.start, selection.end)
                _ui.update { current ->
                    current.copy(
                        selection = null,
                        highlights = (current.highlights + entity).distinctBy { it.id },
                        allHighlights = (current.allHighlights + entity).distinctBy { it.id }
                    )
                }
            }.onFailure { error ->
                _ui.update {
                    it.copy(toast = error.message ?: "No se pudo guardar el subrayado")
                }
            }
        }
    }

    fun removeHighlight(id: String) {
        val state = _ui.value
        val target = state.allHighlights.firstOrNull { it.id == id }
        if (target == null || target.chapterIndex == state.currentChapterIndex) {
            controller.removeHighlight(id)
        }
        viewModelScope.launch {
            runCatching { highlightRepository.delete(id) }
            _ui.update {
                it.copy(
                    selection = null,
                    highlightAction = null,
                    highlights = it.highlights.filterNot { highlight -> highlight.id == id },
                    allHighlights = it.allHighlights.filterNot { highlight -> highlight.id == id }
                )
            }
        }
    }

    override fun onDebug(message: String) {
        android.util.Log.d("LectorReader", message)
    }

    override fun onHighlightApplied(id: String, success: Boolean) {
        if (!success) {
            _ui.update {
                it.copy(
                    toast = "El subrayado se guardó, pero no se pudo dibujar en esta página"
                )
            }
        }
    }

    override fun onHighlightRemoved(id: String, success: Boolean) {
        if (!success) {
            _ui.update { it.copy(toast = "No se pudo quitar el subrayado de la página") }
        }
    }

    override fun onReferenceTapped(references: List<ReaderReference>) {
        if (references.isEmpty()) return
        android.util.Log.d("LectorReader", "onReferenceTapped ${references.map { it.referenceKey }}")
        // Cancela una apertura aplazada anterior para que un toque viejo no
        // pise la referencia más reciente.
        pendingReferenceOpen?.cancel()
        val elapsed = System.currentTimeMillis() - lastReferenceDismissAt
        if (elapsed < REFERENCE_SHEET_COOLDOWN_MS) {
            // El sheet anterior puede seguir animando su cierre: retrasar la
            // apertura evita que ModalBottomSheet ignore la hoja nueva.
            pendingReferenceOpen = viewModelScope.launch {
                delay(REFERENCE_SHEET_COOLDOWN_MS - elapsed)
                _ui.update { it.copy(referencePreview = references) }
            }
        } else {
            _ui.update { it.copy(referencePreview = references) }
        }
    }

    override fun onInternalChapterTapped(chapterIndex: Int, anchor: String?, label: String) {
        val state = _ui.value
        if (chapterIndex !in 0 until state.chapters.size) {
            _ui.update { it.copy(toast = "No se encontró el capítulo destino") }
            return
        }
        val cleanAnchor = anchor?.trim().orEmpty()
        if (cleanAnchor.isNotEmpty()) {
            val matchingRef = findReference(chapterIndex, cleanAnchor)
            if (matchingRef != null && matchingRef.isSheetReference()) {
                onReferenceTapped(listOf(matchingRef))
                return
            }
            if (isSheetAnchor(cleanAnchor)) {
                val fallbackRef = references.firstOrNull { ref ->
                    ref.anchor?.equals(cleanAnchor, ignoreCase = true) == true ||
                        ref.referenceKey.substringAfter(':').equals(cleanAnchor, ignoreCase = true)
                }
                if (fallbackRef != null && fallbackRef.isSheetReference()) {
                    onReferenceTapped(listOf(fallbackRef))
                    return
                }
                _ui.update { it.copy(toast = "No se encontró la referencia $cleanAnchor") }
                return
            }
        }

        // Navegación real a sección, tabla, figura o capítulo
        pushNavPosition()
        if (chapterIndex == state.currentChapterIndex) {
            val found = controller.goToInternalAnchor(cleanAnchor, label)
            if (!found) {
                _ui.update { it.copy(toast = "No se encontró la sección en este capítulo") }
            }
            return
        }
        loadChapter(
            chapterIndex = chapterIndex,
            initialPage = 0,
            anchorFragment = cleanAnchor.ifEmpty { null },
            anchorLabel = label.ifEmpty { null }
        )
    }

    private fun findReference(chapterIndex: Int, anchor: String): ReaderReference? {
        val clean = anchor.trim()
        return references.firstOrNull { ref ->
            (ref.chapterIndex == chapterIndex || ref.referenceKey.startsWith("cr", ignoreCase = true)) &&
                (ref.anchor?.equals(clean, ignoreCase = true) == true ||
                    ref.referenceKey.substringAfter(':').equals(clean, ignoreCase = true))
        } ?: references.firstOrNull { ref ->
            ref.anchor?.equals(clean, ignoreCase = true) == true ||
                ref.referenceKey.substringAfter(':').equals(clean, ignoreCase = true)
        } ?: run {
            val digits = clean.filter { it.isDigit() }
            if (digits.isNotEmpty()) {
                references.firstOrNull { ref ->
                    ref.numericKey == digits &&
                        (ref.chapterIndex == chapterIndex || ref.referenceKey.startsWith("cr", ignoreCase = true))
                }
            } else null
        }
    }

    fun jumpToReferenceSource(reference: ReaderReference) {
        dismissReferencePreview()
        pushNavPosition()
        if (reference.chapterIndex == _ui.value.currentChapterIndex) {
            controller.goToInternalAnchor(reference.anchor ?: "", reference.label, allowSheet = false)
        } else {
            loadChapter(
                chapterIndex = reference.chapterIndex,
                initialPage = 0,
                anchorFragment = reference.anchor,
                anchorLabel = reference.label,
                allowSheet = false
            )
        }
    }

    fun dismissReferencePreview() {
        lastReferenceDismissAt = System.currentTimeMillis()
        _ui.update { it.copy(referencePreview = null) }
    }

    override fun onImageTapped(imageData: ByteArray?, imagePath: String) {
        _ui.update { it.copy(imagePreviewPath = imagePath) }
    }

    fun dismissImagePreview() {
        _ui.update { it.copy(imagePreviewPath = null) }
    }

    override fun onEndOfChapter() {
        val state = _ui.value
        if (state.currentChapterIndex < state.chapters.lastIndex) {
            loadChapter(state.currentChapterIndex + 1, 0)
        } else {
            _ui.update { it.copy(toast = "Has llegado al final del libro") }
        }
    }

    override fun onStartOfChapter() {
        val state = _ui.value
        if (state.currentChapterIndex > 0) {
            loadChapter(state.currentChapterIndex - 1, Int.MAX_VALUE)
        } else {
            _ui.update { it.copy(toast = "Estás al inicio del libro") }
        }
    }

    override fun onTap() {
        _ui.update { it.copy(showTopBar = !it.showTopBar) }
    }

    override fun tapZonesEnabled(): Boolean = _ui.value.tapZones

    /** Como en Play Libros: al pasar página con un gesto la interfaz se retira. */
    override fun onUserPageTurn() {
        if (_ui.value.showTopBar && _ui.value.search == null && !_ui.value.showToc) {
            _ui.update { it.copy(showTopBar = false) }
        }
    }


    private val chapterTextCache = HashMap<Int, String>()

    private fun normalizeForMatch(text: String): String =
        text.replace(Regex("[\\u00A0\\u2007\\u202F]"), " ")
            .replace(Regex("\\s+"), " ")
            .lowercase()

    /**
     * Sección (del índice jerárquico) en la que cae cada fragmento recuperado:
     * el último título del capítulo que aparece antes del fragmento. Permite
     * citar "Sección 3.1.2 …" en vez de solo el capítulo.
     */
    private suspend fun sectionTitles(rag: RagSearchResult): Map<Long, String> {
        val toc = _ui.value.tocItems
        if (toc.isEmpty()) return emptyMap()
        val result = HashMap<Long, String>()
        for (item in rag.results) {
            val chunk = item.chunk
            val chapterTitle = _ui.value.chapters.firstOrNull { it.chapterIndex == chunk.chapterIndex }?.title
            val titles = toc.filter {
                it.chapterIndex == chunk.chapterIndex && it.title.isNotBlank() && it.title != chapterTitle
            }
            if (titles.isEmpty()) continue
            val text = chapterTextCache.getOrPut(chunk.chapterIndex) {
                withContext(Dispatchers.IO) {
                    normalizeForMatch(chapterDao.getChapter(bookId, chunk.chapterIndex)?.plainText.orEmpty())
                }
            }
            val position = text.indexOf(normalizeForMatch(chunk.content.take(90)).trim())
            if (position < 0) continue
            val best = titles.mapNotNull { tocItem ->
                val index = text.lastIndexOf(normalizeForMatch(tocItem.title).trim(), position)
                if (index >= 0) index to tocItem.title else null
            }.maxByOrNull { it.first }
            if (best != null) result[chunk.id] = best.second.trim()
        }
        return result
    }


    fun openSearch() {
        _ui.update { it.copy(search = it.search ?: SearchUi()) }
    }

    fun closeSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(search = null) }
    }

    fun search(query: String) {
        _ui.update { it.copy(search = (it.search ?: SearchUi()).copy(query = query)) }
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _ui.update { it.copy(search = it.search?.copy(hits = emptyList(), searching = false, searched = false)) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(250) // debounce mientras se escribe
            _ui.update { it.copy(search = it.search?.copy(searching = true)) }
            val (hits, truncated) = withContext(Dispatchers.Default) {
                val chapters = withContext(Dispatchers.IO) { chapterDao.search(bookId, trimmed) }
                findHits(chapters, trimmed)
            }
            _ui.update {
                it.copy(search = it.search?.copy(hits = hits, truncated = truncated, searching = false, searched = true))
            }
        }
    }

    private fun findHits(
        chapters: List<com.termino.data.db.ChapterText>,
        query: String
    ): Pair<List<SearchHit>, Boolean> {
        val hits = mutableListOf<SearchHit>()
        for (chapter in chapters) {
            val text = chapter.plainText
            var index = text.indexOf(query, ignoreCase = true)
            while (index >= 0) {
                if (hits.size >= MAX_SEARCH_HITS) return hits to true
                val from = (index - 60).coerceAtLeast(0)
                val to = (index + query.length + 90).coerceAtMost(text.length)
                val rawSnippet = text.substring(from, to)
                val snippet = rawSnippet.replace(Regex("\\s+"), " ")
                // Recalcula la posición tras colapsar espacios.
                val prefix = text.substring(from, index).replace(Regex("\\s+"), " ")
                val start = prefix.length
                hits += SearchHit(
                    chapterIndex = chapter.chapterIndex,
                    chapterTitle = chapter.title,
                    snippet = (if (from > 0) "…" else "") + snippet + (if (to < text.length) "…" else ""),
                    matchStart = start + if (from > 0) 1 else 0,
                    matchEnd = start + query.length + if (from > 0) 1 else 0,
                    locator = text.substring(index, (index + query.length).coerceAtMost(text.length))
                )
                index = text.indexOf(query, index + query.length, ignoreCase = true)
            }
        }
        return hits to false
    }

    fun openSearchHit(hit: SearchHit) {
        pushNavPosition()
        _ui.update { it.copy(search = null) }
        if (hit.chapterIndex == _ui.value.currentChapterIndex && !_ui.value.showPdfPages) {
            controller.findAndReveal(hit.locator)
        } else {
            pendingFindText = hit.locator
            loadChapter(hit.chapterIndex, 0)
        }
    }


    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkDao.observeForBook(bookId).collect { list ->
                _ui.update { it.copy(bookmarks = list) }
            }
        }
    }

    fun toggleBookmark() {
        val state = _ui.value
        val existing = state.currentBookmark
        viewModelScope.launch(Dispatchers.IO) {
            if (existing != null) {
                bookmarkDao.delete(existing.id)
            } else {
                val excerpt = state.visibleText.trim().replace(Regex("\\s+"), " ").take(160)
                bookmarkDao.insert(
                    com.termino.data.db.BookmarkEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        bookId = bookId,
                        chapterIndex = state.currentChapterIndex,
                        pageIndex = state.pageIndex,
                        excerpt = excerpt.ifBlank { state.currentChapter?.title.orEmpty() },
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch(Dispatchers.IO) { bookmarkDao.delete(id) }
    }

    fun openBookmark(bookmark: com.termino.data.db.BookmarkEntity) {
        pushNavPosition()
        _ui.update { it.copy(showToc = false) }
        // El extracto localiza el punto exacto aunque haya cambiado la tipografía.
        pendingFindText = bookmark.excerpt.take(48).takeIf { it.length >= 12 }
        loadChapter(bookmark.chapterIndex, bookmark.pageIndex)
    }


    fun setPdfOriginalPages(enabled: Boolean) {
        _ui.update { it.copy(pdfOriginalPages = enabled) }
        viewModelScope.launch { settingsRepository.savePdfOriginalPages(enabled) }
    }

    /** Página del visor PDF asentada: carga su texto para la IA sin apilar navegación. */
    fun onPdfPageSettled(page: Int) {
        val last = (_ui.value.chapters.size - 1).coerceAtLeast(0)
        val safe = page.coerceIn(0, last)
        if (safe == _ui.value.currentChapterIndex) return
        loadChapter(safe, 0)
        viewModelScope.launch {
            delay(400) // loadChapter publica el estado de forma asíncrona
            saveProgressDebounced()
        }
    }

    /** Móvil: el libro abre sin el asistente encima (no se guarda como preferencia). */
    private var compactLayout = false

    fun useCompactLayout(compact: Boolean) {
        compactLayout = compact
        if (compact && _ui.value.paneMode != AiPaneMode.MINIMIZED) {
            _ui.update { it.copy(paneMode = AiPaneMode.MINIMIZED) }
        }
    }

    fun showChrome(show: Boolean) {
        _ui.update { it.copy(showTopBar = show) }
    }

    override fun onLinkTapped(url: String) {
        if (url.startsWith("#") || (!url.startsWith("http") && url.contains("#"))) {
            _ui.update { it.copy(toast = "No se encontró la sección en este capítulo") }
            return
        }
        _ui.update { it.copy(externalLink = url) }
    }

    fun consumeExternalLink() {
        _ui.update { it.copy(externalLink = null) }
    }

    fun toggleTopBar() {
        _ui.update { it.copy(showTopBar = !it.showTopBar) }
    }

    fun setShowToc(show: Boolean) {
        _ui.update { it.copy(showToc = show) }
    }

    fun jumpToChapter(chapterIndex: Int) {
        val safe = chapterIndex.coerceIn(0, (_ui.value.chapters.size - 1).coerceAtLeast(0))
        if (safe != _ui.value.currentChapterIndex) {
            pushNavPosition()
        }
        _ui.update { it.copy(showToc = false) }
        loadChapter(safe, 0)
    }

    fun nextChapter() {
        val next = _ui.value.currentChapterIndex + 1
        if (next < _ui.value.chapters.size) {
            pushNavPosition()
            loadChapter(next, 0)
        }
    }

    fun previousChapter() {
        val prev = _ui.value.currentChapterIndex - 1
        if (prev >= 0) {
            pushNavPosition()
            loadChapter(prev, 0)
        }
    }

    fun onSelectTocItem(item: TocItem) {
        pushNavPosition()
        _ui.update { it.copy(showToc = false) }
        val anchor = item.anchor
        if (item.chapterIndex == _ui.value.currentChapterIndex && !anchor.isNullOrBlank()) {
            controller.goToInternalAnchor(anchor, item.title, allowSheet = false)
        } else {
            loadChapter(
                chapterIndex = item.chapterIndex,
                initialPage = 0,
                anchorFragment = anchor,
                anchorLabel = item.title,
                allowSheet = false
            )
        }
    }

    fun jumpToHighlight(highlight: HighlightEntity) {
        pushNavPosition()
        _ui.update { it.copy(showToc = false, aiTab = AiTab.NOTES) }
        loadChapter(highlight.chapterIndex, 0, highlight.id)
    }

    fun setTheme(theme: ReaderTheme) {
        val resolved = theme.resolve(appDark)
        _ui.update { it.copy(theme = resolved, themeChoice = theme) }
        controller.updateStyle(resolved, _ui.value.fontSize, _ui.value.lineHeight, _ui.value.font)
        viewModelScope.launch { settingsRepository.saveTheme(theme) }
    }

    /** La app cambió entre claro y oscuro: el tema AUTO del lector la sigue. */
    fun setAppDark(dark: Boolean) {
        if (appDark == dark) return
        appDark = dark
        val choice = _ui.value.themeChoice
        val resolved = choice.resolve(dark)
        if (resolved != _ui.value.theme) {
            _ui.update { it.copy(theme = resolved) }
            controller.updateStyle(resolved, _ui.value.fontSize, _ui.value.lineHeight, _ui.value.font)
        }
    }

    override fun onFontSizePreview(size: Int?) {
        _ui.update { it.copy(fontSizePreview = size) }
    }

    override fun onFontSizeGesture(size: Int) {
        setFontSize(size)
    }

    fun importFont(uri: android.net.Uri) {
        viewModelScope.launch {
            fontRepository.import(uri).fold(
                onSuccess = { font ->
                    setFont(font)
                    _ui.update { it.copy(toast = "Fuente «${font.label}» añadida") }
                },
                onFailure = { error ->
                    _ui.update { it.copy(toast = error.message ?: "No se pudo importar la fuente") }
                }
            )
        }
    }

    fun deleteFont(font: ReaderFont) {
        fontRepository.delete(font)
        if (_ui.value.font == font) setFont(ReaderFont.SERIF)
    }

    fun setFontSize(size: Int) {
        val safe = size.coerceIn(com.termino.core.reader.MIN_FONT_SIZE, com.termino.core.reader.MAX_FONT_SIZE)
        _ui.update { it.copy(fontSize = safe) }
        controller.updateStyle(_ui.value.theme, safe, _ui.value.lineHeight, _ui.value.font)
        viewModelScope.launch { settingsRepository.saveFontSize(safe) }
    }

    fun setLineHeight(value: Float) {
        val safe = value.coerceIn(1.2f, 2.2f)
        _ui.update { it.copy(lineHeight = safe) }
        controller.updateStyle(_ui.value.theme, _ui.value.fontSize, safe, _ui.value.font)
        viewModelScope.launch { settingsRepository.saveLineHeight(safe) }
    }

    fun setFont(font: ReaderFont) {
        _ui.update { it.copy(font = font) }
        controller.updateStyle(_ui.value.theme, _ui.value.fontSize, _ui.value.lineHeight, font)
        viewModelScope.launch { settingsRepository.saveReaderFont(font) }
    }

    fun setEyeCare(enabled: Boolean) {
        _ui.update { it.copy(eyeCare = enabled) }
        viewModelScope.launch { settingsRepository.saveEyeCare(enabled) }
    }

    fun setPaneMode(mode: AiPaneMode) {
        val fraction = if (mode == AiPaneMode.DOCKED && _ui.value.paneFraction < 0.30f) {
            0.36f
        } else {
            _ui.value.paneFraction
        }
        _ui.update { it.copy(paneMode = mode, paneFraction = fraction) }
        viewModelScope.launch { settingsRepository.savePaneMode(mode) }
    }

    fun setPaneFraction(fraction: Float) {
        _ui.update { it.copy(paneFraction = fraction.coerceIn(0f, 0.40f)) }
    }

    fun onPaneDragEnd() {
        val fraction = _ui.value.paneFraction
        if (fraction < 0.28f) {
            _ui.update { it.copy(paneFraction = 0f, paneMode = AiPaneMode.MINIMIZED) }
            viewModelScope.launch {
                settingsRepository.savePaneMode(AiPaneMode.MINIMIZED)
                settingsRepository.savePaneFraction(0f)
            }
        } else {
            val safe = fraction.coerceIn(0.30f, 0.40f)
            _ui.update { it.copy(paneFraction = safe, paneMode = AiPaneMode.DOCKED) }
            viewModelScope.launch {
                settingsRepository.savePaneMode(AiPaneMode.DOCKED)
                settingsRepository.savePaneFraction(safe)
            }
        }
    }

    fun setAiTab(tab: AiTab) {
        _ui.update { it.copy(aiTab = tab) }
    }

    fun setChatInput(value: String) {
        _ui.update { it.copy(chatInput = value) }
    }

    fun sendMessage(text: String = _ui.value.chatInput, quotedText: String? = null) {
        val trimmed = text.trim()
        val state = _ui.value
        if (trimmed.isEmpty() || state.isStreaming) return
        val book = state.book ?: return
        val chapter = state.currentChapter ?: return
        val settings = latestSettings
        if (!canUseAi(settings)) {
            _ui.update {
                it.copy(
                    chatError = "Configura tu API Key y el modelo en Ajustes para usar el asistente."
                )
            }
            return
        }
        val userContent = if (quotedText != null) {
            "> ${quotedText.trim()}\n\n$trimmed"
        } else {
            trimmed
        }
        val chapterTitles = state.chapters.associate { it.chapterIndex to it.title }
        _ui.update {
            it.copy(
                isStreaming = true,
                streamingText = "",
                chatError = null,
                chatInput = "",
                selection = null,
                aiTab = AiTab.CHAT
            )
        }
        chatJob = viewModelScope.launch {
            val full = StringBuilder()
            var failure: String? = null
            try {
                val budget = settings.effectiveContextSize
                val history = chatRepository.getRecent(bookId, budget.historyMessages)
                    .let(chatRepository::toAiMessages)
                chatRepository.addUserMessage(bookId, userContent)
                val baseQuery = if (quotedText != null) "$trimmed $quotedText" else trimmed
                // Preguntas de seguimiento ("¿y eso por qué?") se reescriben con
                // el modelo rápido para buscar bien; el chat recibe la original.
                val query = if (budget.rewriteFollowUps && history.isNotEmpty() && quotedText == null) {
                    aiRepository.rewriteQuery(settings, history, trimmed)
                        ?.let { rewritten -> "$rewritten $trimmed" }
                        ?: baseQuery
                } else {
                    baseQuery
                }
                val rag = runCatching {
                    ragEngine.search(
                        bookId = bookId,
                        query = query,
                        topK = budget.sources,
                        currentChapterIndex = chapter.chapterIndex,
                        passageChars = budget.passageChars,
                        neighborSources = budget.neighborSources,
                        // Antes no se pasaba: el ajuste por la página visible no actuaba.
                        currentVisibleText = _ui.value.visibleText.ifBlank { chapter.plainText.take(4000) }
                    )
                }.getOrDefault(RagSearchResult())
                val sections = runCatching { sectionTitles(rag) }.getOrDefault(emptyMap())
                val highlights = runCatching { highlightRepository.getForBook(bookId) }
                    .getOrDefault(emptyList())
                val context = contextBuilder.buildChatContext(
                    book = book,
                    chapter = chapter,
                    pageIndex = _ui.value.pageIndex,
                    pageText = _ui.value.visibleText,
                    query = query,
                    highlights = highlights,
                    rag = rag,
                    chapterTitles = chapterTitles,
                    sections = sections,
                    contextSize = budget,
                    aiLanguage = settings.aiLanguage
                )
                _ui.update {
                    it.copy(
                        contextSummary = context.summary,
                        lastSources = context.sources
                    )
                }
                aiRepository.streamChat(
                    settings = settings,
                    systemPrompt = context.systemPrompt,
                    history = history,
                    userMessage = userContent
                ).collect { event ->
                    when (event) {
                        is AiStreamEvent.Delta -> {
                            full.append(event.text)
                            _ui.update { it.copy(streamingText = full.toString()) }
                        }
                        is AiStreamEvent.Completed -> {
                            if (full.isEmpty()) full.append(event.fullText)
                        }
                        is AiStreamEvent.Failed -> failure = event.message
                    }
                }
                if (full.isNotBlank()) {
                    chatRepository.addAssistantMessage(
                        bookId = bookId,
                        content = full.toString(),
                        sources = context.sources,
                        model = settings.chatModel
                    )
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (full.isNotBlank() && !suppressPartialSave) {
                        runCatching {
                            chatRepository.addAssistantMessage(
                                bookId = bookId,
                                content = full.toString(),
                                sources = _ui.value.lastSources,
                                model = settings.chatModel
                            )
                        }
                    }
                }
                throw cancelled
            } catch (error: Exception) {
                failure = error.message?.takeIf { it.isNotBlank() }
                    ?: "No se pudo completar la respuesta"
                if (full.isNotBlank()) {
                    runCatching {
                        chatRepository.addAssistantMessage(
                            bookId = bookId,
                            content = full.toString(),
                            sources = _ui.value.lastSources,
                            model = settings.chatModel
                        )
                    }
                }
            } finally {
                _ui.update {
                    it.copy(
                        isStreaming = false,
                        streamingText = null,
                        chatError = failure
                    )
                }
            }
        }
    }

    fun stopStreaming() {
        chatJob?.cancel()
    }

    fun askAboutSelection() {
        val selection = _ui.value.selection ?: return
        val text = selection.text
        if (_ui.value.paneMode == AiPaneMode.MINIMIZED) {
            setPaneMode(AiPaneMode.DOCKED)
        }
        _ui.update { it.copy(selection = null, quickAction = null, aiTab = AiTab.CHAT) }
        sendMessage(
            text = "¿Qué significa este pasaje y cómo se conecta con lo que estoy leyendo?",
            quotedText = text
        )
    }

    fun openQuickAction(type: QuickActionType) {
        val state = _ui.value
        val selection = state.selection ?: return
        val chapter = state.currentChapter ?: return
        val settings = latestSettings
        if (!canUseAi(settings)) {
            _ui.update {
                it.copy(
                    quickAction = QuickActionUi(
                        type = type,
                        title = selection.text.take(80),
                        subtitle = "Configura tu API Key en Ajustes",
                        loading = false,
                        error = "El asistente aún no está configurado."
                    )
                )
            }
            return
        }
        val chapterContext = contextBuilder.buildQuickContext(
            chapter = chapter,
            pageText = state.visibleText,
            selection = selection.text
        )
        val sentence = contextBuilder.sentenceAround(
            source = state.visibleText.ifBlank { chapter.plainText },
            selection = selection.text
        )
        val title = if (type == QuickActionType.DICTIONARY) {
            selection.text.take(80)
        } else {
            "Explicar pasaje"
        }
        val subtitle = if (type == QuickActionType.DICTIONARY) {
            "Definición exacta según el contexto"
        } else {
            "Desglose del pasaje y su relación con el capítulo"
        }
        _ui.update {
            it.copy(
                quickAction = QuickActionUi(
                    type = type,
                    title = title,
                    subtitle = subtitle,
                    loading = true
                )
            )
        }
        quickJob?.cancel()
        quickJob = viewModelScope.launch {
            val full = StringBuilder()
            try {
                val flow = if (type == QuickActionType.DICTIONARY) {
                    aiRepository.streamDefinition(
                        settings = settings,
                        word = selection.text,
                        sentence = sentence,
                        chapterContext = chapterContext
                    )
                } else {
                    aiRepository.streamExplanation(
                        settings = settings,
                        passage = selection.text,
                        chapterContext = chapterContext,
                        bookTitle = state.book?.title ?: ""
                    )
                }
                flow.collect { event ->
                    when (event) {
                        is AiStreamEvent.Delta -> {
                            full.append(event.text)
                            _ui.update {
                                it.copy(
                                    quickAction = it.quickAction?.copy(
                                        content = full.toString(),
                                        loading = false
                                    )
                                )
                            }
                        }
                        is AiStreamEvent.Completed -> {
                            if (full.isEmpty()) full.append(event.fullText)
                            _ui.update {
                                it.copy(
                                    quickAction = it.quickAction?.copy(
                                        content = full.toString(),
                                        loading = false
                                    )
                                )
                            }
                        }
                        is AiStreamEvent.Failed -> {
                            _ui.update {
                                it.copy(
                                    quickAction = it.quickAction?.copy(
                                        loading = false,
                                        error = event.message
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.update {
                    it.copy(
                        quickAction = it.quickAction?.copy(
                            loading = false,
                            error = error.message ?: "No se pudo consultar la IA"
                        )
                    )
                }
            }
        }
    }

    fun dismissQuickAction() {
        quickJob?.cancel()
        _ui.update { it.copy(quickAction = null) }
    }

    fun askFromQuickAction() {
        val text = _ui.value.selection?.text ?: _ui.value.quickAction?.title ?: return
        dismissQuickAction()
        if (_ui.value.paneMode == AiPaneMode.MINIMIZED) {
            setPaneMode(AiPaneMode.DOCKED)
        }
        _ui.update { it.copy(selection = null, aiTab = AiTab.CHAT) }
        sendMessage(
            text = "¿Qué significa este pasaje y cómo se conecta con lo que estoy leyendo?",
            quotedText = text
        )
    }

    fun clearChat() {
        suppressPartialSave = true
        val job = chatJob
        job?.cancel()
        viewModelScope.launch {
            job?.join()
            chatRepository.clear(bookId)
            _ui.update {
                it.copy(
                    chatMessages = emptyList(),
                    streamingText = null,
                    isStreaming = false,
                    chatError = null,
                    contextSummary = "",
                    lastSources = emptyList()
                )
            }
            suppressPartialSave = false
        }
    }

    fun clearToast() {
        _ui.update { it.copy(toast = null) }
    }

    private fun canUseAi(settings: AppSettings): Boolean = settings.isConfigured

    override fun onCleared() {
        chatJob?.cancel()
        quickJob?.cancel()
        progressJob?.cancel()
        pendingReferenceOpen?.cancel()
        super.onCleared()
    }

    companion object {
        private const val REFERENCE_SHEET_COOLDOWN_MS = 350L
        private const val MAX_SEARCH_HITS = 300

        fun factory(app: TerminoApp, bookId: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val locator = app.serviceLocator
                    ReaderViewModel(
                        appContext = app,
                        bookId = bookId,
                        bookRepository = locator.bookRepository,
                        chapterDao = locator.database.chapterDao(),
                        referenceDao = locator.database.referenceDao(),
                        bookmarkDao = locator.database.bookmarkDao(),
                        fontRepository = locator.fontRepository,
                        highlightRepository = locator.highlightRepository,
                        chatRepository = locator.chatRepository,
                        settingsRepository = locator.settingsRepository,
                        aiRepository = locator.aiRepository,
                        ragEngine = locator.ragEngine,
                        contextBuilder = locator.readingContextBuilder,
                        epubProcessor = locator.epubProcessor,
                        controller = NativeReaderController(app, java.io.File(app.filesDir, "books/$bookId/assets"))
                    )
                }
            }
    }
}

private fun com.termino.data.db.HighlightEntity.toReaderHighlight() =
    com.termino.core.reader.ReaderHighlight(
        id = id,
        startOffset = startOffset,
        endOffset = endOffset,
        color = color
    )
