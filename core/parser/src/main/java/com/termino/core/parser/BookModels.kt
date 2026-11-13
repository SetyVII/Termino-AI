package com.termino.core.parser

enum class BookFormat {
    EPUB,
    PDF;

    companion object {
        fun fromStorage(value: String?): BookFormat =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: EPUB
    }
}

/**
 * Display labels the parsers write into the parsed book (chapter and page
 * labels, reference kinds, fallbacks). Defaults are English; the app passes
 * localized values so stored labels match the UI language.
 * [chapter], [section] and [page] are format strings taking one `%s` argument.
 */
data class ParserLabels(
    val chapter: String = "Chapter %s",
    val section: String = "Section %s",
    val sectionReference: String = "Section",
    val page: String = "Page %s",
    val affiliation: String = "Affiliation",
    val contact: String = "Contact",
    val figure: String = "Figure",
    val table: String = "Table",
    val bibliography: String = "Bibliography",
    val untitledBook: String = "Untitled book",
    val untitledPdf: String = "PDF document",
    val unknownAuthor: String = "Unknown author",
    val emptyPdfPage: String = "This page has no selectable text. It may be a scanned page or an image."
) {
    fun chapter(number: Any): String = chapter.format(number)
    fun section(number: Any): String = section.format(number)
    fun page(number: Any): String = page.format(number)
}

/** Table of contents entry of a book; [level] is the hierarchy depth. */
data class TocItem(
    val title: String,
    val chapterIndex: Int,
    val anchor: String? = null,
    val level: Int = 0
)
