package com.termino.core.parser

enum class BookFormat {
    EPUB,
    PDF;

    companion object {
        fun fromStorage(value: String?): BookFormat =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: EPUB
    }
}

/** Entrada del índice (TOC) de un libro; [level] indica la profundidad jerárquica. */
data class TocItem(
    val title: String,
    val chapterIndex: Int,
    val anchor: String? = null,
    val level: Int = 0
)
