package com.termino.ui.reader

import com.termino.core.parser.TocItem
import com.termino.core.reader.ReaderFont
import com.termino.core.reader.ReaderReference
import com.termino.core.reader.ReaderTheme
import com.termino.ai.SourceRef
import com.termino.data.db.BookEntity
import com.termino.data.db.ChapterEntity
import com.termino.data.db.ChapterSummary
import com.termino.data.db.ChatMessageEntity
import com.termino.data.db.HighlightEntity
import com.termino.data.model.AiPaneMode
import com.termino.data.model.AiTab
import com.termino.data.model.QuickActionType

data class SelectionUi(
    val text: String,
    val start: Int,
    val end: Int,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val existingHighlightId: String? = null
)

data class QuickActionUi(
    val type: QuickActionType,
    val title: String,
    val subtitle: String,
    val content: String = "",
    val loading: Boolean = true,
    val error: String? = null
)

data class ReaderUiState(
    val loading: Boolean = true,
    val book: BookEntity? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val tocItems: List<TocItem> = emptyList(),
    val currentChapter: ChapterEntity? = null,
    val currentChapterIndex: Int = 0,
    val pageIndex: Int = 0,
    val totalPages: Int = 1,
    val visibleText: String = "",
    val highlights: List<HighlightEntity> = emptyList(),
    val allHighlights: List<HighlightEntity> = emptyList(),
    val selection: SelectionUi? = null,
    val highlightAction: HighlightEntity? = null,
    val referencePreview: List<ReaderReference>? = null,
    val imagePreviewPath: String? = null,
    val showTopBar: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontSize: Int = 19,
    val lineHeight: Float = 1.65f,
    val font: ReaderFont = ReaderFont.SERIF,
    val paneMode: AiPaneMode = AiPaneMode.DOCKED,
    val paneFraction: Float = 0.36f,
    val aiTab: AiTab = AiTab.CHAT,
    val showToc: Boolean = false,
    val chatMessages: List<ChatMessageEntity> = emptyList(),
    val streamingText: String? = null,
    val isStreaming: Boolean = false,
    val chatInput: String = "",
    val chatError: String? = null,
    val contextSummary: String = "",
    val lastSources: List<SourceRef> = emptyList(),
    val quickAction: QuickActionUi? = null,
    val pendingHighlightId: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val toast: String? = null,
    val externalLink: String? = null,
    val eyeCare: Boolean = false,
    val canNavigateBack: Boolean = false,
    val tapZones: Boolean = true,
    val volumeKeysTurnPages: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** PDF shown as the original rendered page (not applicable to EPUB). */
    val pdfOriginalPages: Boolean = true,
    val bookmarks: List<com.termino.data.db.BookmarkEntity> = emptyList(),
    val search: SearchUi? = null,
    /** Chosen theme (may be AUTO); [theme] is always the resolved one. */
    val themeChoice: ReaderTheme = ReaderTheme.LIGHT,
    val fonts: List<ReaderFont> = emptyList(),
    /** Size shown while pinching to change the font size. */
    val fontSizePreview: Int? = null
) {
    val isPdf: Boolean get() = book?.format.equals("PDF", true)
    val showPdfPages: Boolean get() = isPdf && pdfOriginalPages
    val currentBookmark: com.termino.data.db.BookmarkEntity?
        get() = bookmarks.firstOrNull {
            it.chapterIndex == currentChapterIndex && (isPdf || it.pageIndex == pageIndex)
        }
}

data class SearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    /** Snippet with the match at [matchStart, matchEnd). */
    val snippet: String,
    val matchStart: Int,
    val matchEnd: Int,
    /** Exact text around the match, used to locate it when jumping. */
    val locator: String
)

data class SearchUi(
    val query: String = "",
    val searching: Boolean = false,
    val hits: List<SearchHit> = emptyList(),
    val truncated: Boolean = false,
    val searched: Boolean = false
)
