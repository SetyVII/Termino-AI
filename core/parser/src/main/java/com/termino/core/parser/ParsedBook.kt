package com.termino.core.parser


data class ParsedChapter(
    val chapterIndex: Int,
    val title: String,
    val pageLabel: String,
    val html: String,
    val plainText: String,
    val wordCount: Int
)

data class ParsedAsset(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray
)

data class ParsedReference(
    val referenceKey: String,
    val numericKey: String?,
    val label: String,
    val content: String,
    val chapterIndex: Int,
    val anchor: String?
)

data class ParsedBook(
    val title: String,
    val author: String,
    val language: String?,
    val description: String?,
    val format: BookFormat,
    val chapters: List<ParsedChapter>,
    val assets: List<ParsedAsset>,
    val coverBytes: ByteArray?,
    val coverExtension: String?,
    val totalPages: Int,
    val references: List<ParsedReference> = emptyList()
) {
    val totalWords: Int get() = chapters.sumOf { it.wordCount }
}
