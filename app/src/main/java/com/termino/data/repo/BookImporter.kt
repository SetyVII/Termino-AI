package com.termino.data.repo

import com.termino.R
import com.termino.core.parser.BookFormat
import com.termino.core.parser.EpubProcessor
import com.termino.core.parser.ParsedBook
import com.termino.core.parser.PdfCoverRenderer
import com.termino.core.parser.PdfProcessor
import com.termino.core.rag.HybridRagEngine
import com.termino.core.rag.IndexChapter
import android.content.Context
import android.net.Uri
import com.termino.data.db.BookEntity
import com.termino.data.db.BookReferenceEntity
import com.termino.data.db.ChapterEntity
import com.termino.data.db.TerminoDatabase
import androidx.room.withTransaction
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

sealed interface ImportSource {
    data class ContentUri(val uri: Uri, val displayName: String?) : ImportSource
    data class Asset(val path: String, val displayName: String?) : ImportSource
}

data class ImportResult(
    val bookId: String,
    val warning: String? = null
)

class BookImporter(
    private val context: Context,
    private val database: TerminoDatabase,
    private val epubProcessor: EpubProcessor,
    private val pdfProcessor: PdfProcessor,
    private val ragEngine: HybridRagEngine
) {
    suspend fun importBook(
        source: ImportSource,
        onProgress: (Int, String) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val bookId = UUID.randomUUID().toString()
        val bookDir = File(context.filesDir, "books/$bookId")
        bookDir.mkdirs()
        var bookInserted = false
        try {
            onProgress(1, context.getString(R.string.import_copying))
            val displayName = when (source) {
                is ImportSource.ContentUri -> source.displayName
                is ImportSource.Asset -> source.displayName
            }
            val tempFile = File(bookDir, "source.tmp")
            val input = when (source) {
                is ImportSource.ContentUri -> context.contentResolver.openInputStream(source.uri)
                is ImportSource.Asset -> context.assets.open(source.path)
            } ?: throw IllegalArgumentException(context.getString(R.string.import_error_open))
            input.use { copyToFile(it, tempFile) }
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalArgumentException(context.getString(R.string.import_error_empty))
            }

            val format = detectFormat(tempFile, displayName)
            val bookFile = File(
                bookDir,
                if (format == BookFormat.EPUB) "book.epub" else "book.pdf"
            )
            if (!tempFile.renameTo(bookFile)) {
                tempFile.copyTo(bookFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(10, context.getString(R.string.import_parsing, format.name))
            val parsed = when (format) {
                BookFormat.EPUB -> FileInputStream(bookFile).use { stream ->
                    epubProcessor.parse(stream, bookDir)
                }
                BookFormat.PDF -> FileInputStream(bookFile).use { stream ->
                    pdfProcessor.parse(stream, displayName ?: bookFile.name)
                }
            }
            if (parsed.chapters.isEmpty()) {
                throw IllegalArgumentException(context.getString(R.string.import_error_no_content))
            }

            saveAssets(parsed, bookDir, basePercent = 34, spanPercent = 4, onProgress)

            val coverFile = saveCover(parsed, bookFile, bookDir)
            val now = System.currentTimeMillis()
            val book = BookEntity(
                id = bookId,
                title = parsed.title.ifBlank { displayName ?: context.getString(R.string.parser_untitled_book) },
                author = parsed.author.ifBlank { context.getString(R.string.parser_unknown_author) },
                format = parsed.format.name,
                filePath = bookFile.absolutePath,
                coverPath = coverFile?.absolutePath,
                description = normalizeDescription(parsed.description),
                language = parsed.language,
                totalChapters = parsed.chapters.size,
                totalPages = parsed.totalPages,
                totalWords = parsed.totalWords,
                addedAt = now,
                lastOpenedAt = now,
                lastChapterIndex = 0,
                lastPageIndex = 0,
                progress = 0f,
                ragIndexed = false,
                ragError = null
            )
            // Book, chapters and references are inserted as one atomic
            // transaction, in batches, so a mid-import failure never leaves
            // a half-populated book behind.
            onProgress(39, context.getString(R.string.import_saving_chapters))
            database.withTransaction {
                database.bookDao().insert(book)
                insertChaptersAndReferences(bookId, parsed)
            }
            bookInserted = true

            onProgress(41, context.getString(R.string.import_building_index))
            val warning = indexRag(bookId, parsed, context.getString(R.string.import_book_ready), onProgress)

            File(bookDir, "epub_extract").deleteRecursively()
            onProgress(100, warning ?: context.getString(R.string.import_ready))
            ImportResult(bookId = bookId, warning = warning)
        } catch (error: Exception) {
            if (bookInserted) {
                runCatching { database.bookDao().deleteById(bookId) }
            }
            bookDir.deleteRecursively()
            throw error
        }
    }

    /**
     * Re-parses an already imported book from its stored source file and
     * refreshes chapters, references, images, cover and the RAG index while
     * keeping the same [bookId], so highlights and chat history survive.
     *
     * Typical use: picking up parser upgrades (e.g. internal links rewritten
     * to the termino://chapter/<n> scheme) without re-importing the file.
     *
     * Behaviour:
     * - The book row is updated with the freshly parsed metadata and its
     *   [BookEntity.lastChapterIndex] / [BookEntity.lastPageIndex] are
     *   clamped to the new chapter/page bounds.
     * - Chapters and book references are deleted and re-inserted in one
     *   atomic transaction. Highlights and chat messages are never touched;
     *   if the book now has fewer chapters, highlights of removed chapters
     *   stay in the database but simply stop being rendered.
     * - The assets directory is backed up (rename) before being rewritten
     *   and restored if anything fails, so the old, still-referenced
     *   chapter HTML keeps working. A stale cover file from a previous parse
     *   is only removed when a new cover is produced.
     * - The RAG index is rebuilt via [HybridRagEngine.indexBook]; a failure
     *   is reported as [ImportResult.warning] instead of aborting.
     *
     * @throws IllegalArgumentException if the book does not exist or its
     *         source file is missing/unreadable.
     */
    suspend fun reprocessBook(
        bookId: String,
        onProgress: (Int, String) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        val book = database.bookDao().getById(bookId)
            ?: throw IllegalArgumentException(context.getString(R.string.import_error_missing_book))
        val bookFile = File(book.filePath)
        if (!bookFile.isFile || !bookFile.canRead()) {
            throw IllegalArgumentException(context.getString(R.string.import_error_missing_source))
        }
        val bookDir = (bookFile.parentFile ?: File(context.filesDir, "books/$bookId"))
            .apply { mkdirs() }
        // Clear leftovers from a previously interrupted run before parsing.
        File(bookDir, "epub_extract").deleteRecursively()

        onProgress(10, context.getString(R.string.import_parsing, book.format))
        val format = runCatching { BookFormat.valueOf(book.format.trim().uppercase()) }
            .getOrNull()
            ?: detectFormat(bookFile, null)
        val parsed = when (format) {
            BookFormat.EPUB -> FileInputStream(bookFile).use { stream ->
                epubProcessor.parse(stream, bookDir)
            }
            BookFormat.PDF -> FileInputStream(bookFile).use { stream ->
                pdfProcessor.parse(stream, book.title)
            }
        }
        if (parsed.chapters.isEmpty()) {
            throw IllegalArgumentException(context.getString(R.string.import_error_no_content))
        }

        // Back up the assets directory before clearing it so a mid-way
        // failure can restore the files the still-current chapter HTML
        // references. If the rename is not possible we comply with the
        // "clean before write" contract and simply drop the old assets.
        val assetsDir = File(bookDir, "assets")
        val assetsBackup = File(bookDir, "assets_reprocess_backup")
        assetsBackup.deleteRecursively()
        val assetsBackedUp = assetsDir.isDirectory && assetsDir.renameTo(assetsBackup)
        if (assetsDir.isDirectory && !assetsBackedUp) {
            assetsDir.deleteRecursively()
        }
        try {
            saveAssets(parsed, bookDir, basePercent = 34, spanPercent = 4, onProgress)

            val coverFile = saveCover(parsed, bookFile, bookDir)

            onProgress(39, context.getString(R.string.import_updating_chapters))
            val maxChapterIndex = parsed.chapters.size - 1
            val maxPageIndex = (parsed.totalPages - 1).coerceAtLeast(0)
            val updated = book.copy(
                title = parsed.title.ifBlank { book.title },
                author = parsed.author.ifBlank { book.author },
                format = parsed.format.name,
                filePath = bookFile.absolutePath,
                coverPath = coverFile?.absolutePath ?: book.coverPath,
                description = normalizeDescription(parsed.description),
                language = parsed.language ?: book.language,
                totalChapters = parsed.chapters.size,
                totalPages = parsed.totalPages,
                totalWords = parsed.totalWords,
                lastChapterIndex = book.lastChapterIndex.coerceIn(0, maxChapterIndex),
                lastPageIndex = book.lastPageIndex.coerceIn(0, maxPageIndex),
                ragIndexed = false,
                ragError = null
            )
            // Metadata refresh and content replacement are atomic: the book
            // row keeps its id, so highlights/chat keep pointing at it.
            database.withTransaction {
                database.bookDao().update(updated)
                database.chapterDao().deleteByBook(bookId)
                database.referenceDao().deleteByBook(bookId)
                insertChaptersAndReferences(bookId, parsed)
            }

            onProgress(41, context.getString(R.string.import_building_index))
            val warning = indexRag(bookId, parsed, context.getString(R.string.import_book_updated) + ".", onProgress)

            File(bookDir, "epub_extract").deleteRecursively()
            assetsBackup.deleteRecursively()
            onProgress(100, warning ?: context.getString(R.string.import_book_updated))
            ImportResult(bookId = bookId, warning = warning)
        } catch (error: Exception) {
            if (assetsBackedUp) {
                assetsDir.deleteRecursively()
                assetsBackup.renameTo(assetsDir)
            }
            File(bookDir, "epub_extract").deleteRecursively()
            throw error
        }
    }

    private suspend fun insertChaptersAndReferences(bookId: String, parsed: ParsedBook) {
        parsed.chapters.chunked(200).forEach { batch ->
            database.chapterDao().insertAll(
                batch.map { chapter ->
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = chapter.chapterIndex,
                        title = chapter.title,
                        pageLabel = chapter.pageLabel,
                        html = chapter.html,
                        plainText = chapter.plainText,
                        wordCount = chapter.wordCount
                    )
                }
            )
        }
        if (parsed.references.isNotEmpty()) {
            database.referenceDao().insertAll(
                parsed.references.chunked(250).flatMap { batch ->
                    batch.map { reference ->
                        BookReferenceEntity(
                            bookId = bookId,
                            referenceKey = reference.referenceKey,
                            numericKey = reference.numericKey,
                            label = reference.label,
                            content = reference.content,
                            chapterIndex = reference.chapterIndex,
                            anchor = reference.anchor
                        )
                    }
                }
            )
        }
    }

    /**
     * Writes the parsed assets under `books/<id>/assets` reporting progress
     * within the [basePercent, basePercent + spanPercent] window.
     */
    private fun saveAssets(
        parsed: ParsedBook,
        bookDir: File,
        basePercent: Int,
        spanPercent: Int,
        onProgress: (Int, String) -> Unit
    ) {
        val totalAssets = parsed.assets.size
        var reportedAssetPercent = -1
        parsed.assets.forEachIndexed { index, asset ->
            val output = File(bookDir, "assets/${asset.name}")
            output.parentFile?.mkdirs()
            output.writeBytes(asset.bytes)
            val percent = basePercent + ((index + 1) * spanPercent / totalAssets.coerceAtLeast(1))
            if (percent != reportedAssetPercent) {
                reportedAssetPercent = percent
                onProgress(
                    percent.coerceAtMost(basePercent + spanPercent),
                    if (totalAssets > 1) {
                        context.getString(R.string.import_saving_images_count, index + 1, totalAssets)
                    } else {
                        context.getString(R.string.import_saving_images)
                    }
                )
            }
        }
    }

    /**
     * Writes the cover at the book directory root. Stale covers left by a
     * previous parse (e.g. a different extension) are only removed when a
     * new cover is actually produced, so reprocessing never accumulates
     * files nor loses a cover the new parse could not regenerate.
     *
     * The embedded cover is written to a temporary file first and only
     * atomically renamed to its final name after a successful write, so a
     * mid-write failure leaves the previous cover intact.
     */
    private fun saveCover(parsed: ParsedBook, bookFile: File, bookDir: File): File? {
        val bytes = parsed.coverBytes
        if (bytes != null && bytes.isNotEmpty()) {
            val extension = parsed.coverExtension?.lowercase()?.ifBlank { "jpg" } ?: "jpg"
            val cover = File(bookDir, "cover.$extension")
            val temp = File(bookDir, "cover.$extension.tmp")
            try {
                temp.writeBytes(bytes)
                if (!temp.renameTo(cover)) {
                    temp.copyTo(cover, overwrite = true)
                    temp.delete()
                }
            } catch (error: Exception) {
                // The previous cover (if any) stays intact: only the
                // half-written temporary file is removed.
                temp.delete()
                throw error
            }
            deleteStaleCovers(bookDir, keep = cover.name)
            return cover
        }
        if (parsed.format == BookFormat.PDF) {
            val cover = File(bookDir, "cover.png")
            if (PdfCoverRenderer.render(bookFile, cover)) {
                deleteStaleCovers(bookDir, keep = cover.name)
                return cover
            }
        }
        return null
    }

    private fun deleteStaleCovers(bookDir: File, keep: String? = null) {
        bookDir.listFiles { file ->
            file.isFile && file.name.startsWith("cover.") && file.name != keep
        }?.forEach { it.delete() }
    }

    private fun normalizeDescription(raw: String?): String? =
        raw?.let { runCatching { Jsoup.parse(it).text() }.getOrDefault(it) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * Rebuilds the hybrid RAG index for a book. A failure while indexing is
     * downgraded to a warning (reflected in [ImportResult.warning] and the
     * book's ragError column) instead of failing the whole operation.
     */
    private suspend fun indexRag(
        bookId: String,
        parsed: ParsedBook,
        readyMessage: String,
        onProgress: (Int, String) -> Unit
    ): String? {
        return try {
            ragEngine.indexBook(
                bookId = bookId,
                chapters = parsed.chapters.map {
                    IndexChapter(
                        chapterIndex = it.chapterIndex,
                        pageLabel = it.pageLabel,
                        text = it.plainText
                    )
                }
            ) { indexProgress ->
                onProgress(
                    41 + (indexProgress * 57 / 100),
                    context.getString(R.string.import_indexing)
                )
            }
            database.bookDao().updateRagState(bookId, true, null)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (indexError: Exception) {
            val message = indexError.message?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.import_index_failed)
            database.bookDao().updateRagState(bookId, false, message)
            context.getString(R.string.import_index_failed_warning, readyMessage, message)
        }
    }

    private fun copyToFile(input: InputStream, output: File) {
        output.outputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > 768L * 1024L * 1024L) {
                    throw IllegalArgumentException(context.getString(R.string.import_error_too_large))
                }
                stream.write(buffer, 0, read)
            }
        }
    }

    private fun detectFormat(file: File, displayName: String?): BookFormat {
        val extension = displayName?.substringAfterLast('.', "")?.lowercase()
        if (extension == "epub") return BookFormat.EPUB
        if (extension == "pdf") return BookFormat.PDF
        val header = ByteArray(8)
        val read = FileInputStream(file).use { it.read(header) }
        if (read >= 4) {
            if (header[0] == 'P'.code.toByte() &&
                header[1] == 'K'.code.toByte() &&
                header[2] == 3.toByte() &&
                header[3] == 4.toByte()
            ) {
                return BookFormat.EPUB
            }
            if (header[0] == '%'.code.toByte() &&
                header[1] == 'P'.code.toByte() &&
                header[2] == 'D'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            ) {
                return BookFormat.PDF
            }
        }
        throw IllegalArgumentException(context.getString(R.string.import_error_unsupported))
    }
}
