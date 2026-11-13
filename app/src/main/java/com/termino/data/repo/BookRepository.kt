package com.termino.data.repo

import com.termino.R
import com.termino.core.rag.HybridRagEngine
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.termino.data.db.BookEntity
import com.termino.data.db.TerminoDatabase
import com.termino.data.model.ImportJob
import com.termino.data.model.ImportJobState
import com.termino.data.worker.BookImportWorker
import com.termino.data.worker.BookReprocessWorker
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BookRepository(
    private val context: Context,
    private val database: TerminoDatabase,
    private val ragEngine: HybridRagEngine
) {
    val books: Flow<List<BookEntity>> = database.bookDao().observeAll()

    suspend fun getBook(id: String): BookEntity? = database.bookDao().getById(id)

    fun observeBook(id: String): Flow<BookEntity?> = database.bookDao().observeById(id)

    suspend fun touch(bookId: String) {
        database.bookDao().touch(bookId, System.currentTimeMillis())
    }

    suspend fun updateProgress(
        bookId: String,
        chapterIndex: Int,
        pageIndex: Int,
        progress: Float
    ) {
        database.bookDao().updateProgress(
            id = bookId,
            chapterIndex = chapterIndex,
            pageIndex = pageIndex,
            progress = progress.coerceIn(0f, 1f),
            time = System.currentTimeMillis()
        )
    }

    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        ragEngine.clearIndex(bookId)
        database.bookDao().deleteById(bookId)
        File(context.filesDir, "books/$bookId").deleteRecursively()
    }

    fun enqueueImport(uri: Uri, displayName: String?): String {
        runCatching {
            if (uri.scheme == "content") {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val jobId = UUID.randomUUID().toString()
        val name = displayName?.takeIf { it.isNotBlank() }
            ?: resolveDisplayName(uri)
            ?: context.getString(R.string.import_default_name)
        ImportStatusStore.add(
            ImportJob(
                id = jobId,
                displayName = name,
                state = ImportJobState.QUEUED,
                message = context.getString(R.string.import_queued)
            )
        )
        val request = OneTimeWorkRequestBuilder<BookImportWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookImportWorker.KEY_JOB_ID, jobId)
                    .putString(BookImportWorker.KEY_URI, uri.toString())
                    .putString(BookImportWorker.KEY_NAME, name)
                    .putBoolean(BookImportWorker.KEY_ASSET, false)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "termino_import_$jobId",
            ExistingWorkPolicy.KEEP,
            request
        )
        return jobId
    }

    fun enqueueSample(assetPath: String, displayName: String): String {
        val jobId = UUID.randomUUID().toString()
        ImportStatusStore.add(
            ImportJob(
                id = jobId,
                displayName = displayName,
                state = ImportJobState.QUEUED,
                message = context.getString(R.string.import_queued)
            )
        )
        val request = OneTimeWorkRequestBuilder<BookImportWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookImportWorker.KEY_JOB_ID, jobId)
                    .putString(BookImportWorker.KEY_URI, assetPath)
                    .putString(BookImportWorker.KEY_NAME, displayName)
                    .putBoolean(BookImportWorker.KEY_ASSET, true)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "termino_import_$jobId",
            ExistingWorkPolicy.KEEP,
            request
        )
        return jobId
    }

    /**
     * Enqueues a re-analysis of an already imported book: chapters,
     * references, images, cover and RAG index are processed again under the
     * same [bookId], keeping highlights and chat.
     *
     * The job in [ImportStatusStore] uses a deterministic per-book id
     * ("reprocess_<bookId>"), so requesting it twice updates the same status
     * card instead of creating orphan notices. The unique work is enqueued
     * with [ExistingWorkPolicy.KEEP]: if a re-analysis for that book is
     * already pending or running, WorkManager ignores the duplicate and the
     * book is never processed twice in parallel; once the previous work has
     * finished, KEEP lets it be enqueued again normally.
     *
     * @return the status job id, or null if the book does not exist.
     */
    suspend fun enqueueReprocess(bookId: String): String? {
        val book = database.bookDao().getById(bookId) ?: return null
        val jobId = "reprocess_$bookId"
        ImportStatusStore.add(
            ImportJob(
                id = jobId,
                displayName = context.getString(R.string.import_reprocess_name, book.title),
                state = ImportJobState.QUEUED,
                message = context.getString(R.string.import_queued)
            )
        )
        val request = OneTimeWorkRequestBuilder<BookReprocessWorker>()
            .setInputData(
                Data.Builder()
                    .putString(BookReprocessWorker.KEY_JOB_ID, jobId)
                    .putString(BookReprocessWorker.KEY_BOOK_ID, bookId)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "termino_reprocess_$bookId",
            ExistingWorkPolicy.KEEP,
            request
        )
        return jobId
    }

    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) return cursor.getString(index)
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
