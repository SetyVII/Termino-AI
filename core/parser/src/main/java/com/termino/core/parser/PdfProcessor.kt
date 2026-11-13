package com.termino.core.parser

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PdfProcessor(
    private val context: Context,
    private val labels: ParserLabels = ParserLabels()
) {

    companion object {
        /** Joins words hyphenated across a line break. */
        private val dehyphenationRegex = Regex("(\\p{Ll})-[ \\t]*\\n[ \\t]*(\\p{Ll})")
    }

    suspend fun parse(input: InputStream, displayName: String?): ParsedBook =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(input).use { document ->
                val metadata = document.documentInformation
                val fallbackTitle = displayName
                    ?.substringBeforeLast('.')
                    ?.replace('_', ' ')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: labels.untitledPdf
                val title = metadata?.title?.trim()?.takeIf { it.isNotBlank() } ?: fallbackTitle
                val author = metadata?.author?.trim()?.takeIf { it.isNotBlank() }
                    ?: labels.unknownAuthor
                val description = metadata?.subject?.trim()?.takeIf { it.isNotBlank() }

                val pageCount = document.numberOfPages
                if (pageCount <= 0) {
                    throw IllegalArgumentException("The PDF has no pages")
                }

                val chapters = mutableListOf<ParsedChapter>()
                // One stripper for the whole document: PDFTextStripper is
                // reused across pages by moving startPage/endPage, which skips
                // the per-page re-instantiation cost (internal caches are
                // rebuilt per getText call, not per stripper instance).
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                for (page in 0 until pageCount) {
                    stripper.startPage = page + 1
                    stripper.endPage = page + 1
                    val raw = runCatching { stripper.getText(document) }.getOrDefault("")
                    val paragraphs = normalizePdfText(raw)
                    val plainText = paragraphs.joinToString("\n\n")
                    val wordCount = TextUtils.wordCount(plainText)
                    val html = if (paragraphs.isEmpty()) {
                        "<p class=\"empty-page\">${escapeHtml(labels.emptyPdfPage)}</p>"
                    } else {
                        paragraphs.joinToString(separator = "") { paragraph ->
                            "<p>${escapeHtml(paragraph)}</p>"
                        }
                    }
                    chapters.add(
                        ParsedChapter(
                            chapterIndex = page,
                            title = labels.page(page + 1),
                            pageLabel = labels.page(page + 1),
                            html = html,
                            plainText = plainText,
                            wordCount = wordCount
                        )
                    )
                }

                ParsedBook(
                    title = title,
                    author = author,
                    language = null,
                    description = description,
                    format = BookFormat.PDF,
                    chapters = chapters,
                    assets = emptyList(),
                    coverBytes = null,
                    coverExtension = null,
                    totalPages = pageCount,
                    references = emptyList()
                )
            }
        }

    private fun normalizePdfText(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
        val dehyphenated = normalized.replace(dehyphenationRegex, "$1$2")
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) paragraphs.add(TextUtils.collapseWhitespace(text))
            current.setLength(0)
        }
        dehyphenated.split('\n').forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flush()
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(trimmed)
            }
        }
        flush()
        return paragraphs
    }

    private fun escapeHtml(value: String): String = buildString(value.length + 16) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }
}
