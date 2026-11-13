package com.termino.data.repo

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
            ?: "Libro importado"
        ImportStatusStore.add(
            ImportJob(
                id = jobId,
                displayName = name,
                state = ImportJobState.QUEUED,
                message = "En cola"
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
                message = "En cola"
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
     * Encola el reanálisis de un libro ya importado: se vuelven a procesar
     * capítulos, referencias, imágenes, portada e índice RAG con el mismo
     * [bookId], conservando subrayados y chat.
     *
     * El job en [ImportStatusStore] usa un id determinista por libro
     * ("reprocess_<bookId>") y nombre "Reanalizando «<título>»", de modo que
     * pedirlo dos veces actualiza la misma tarjeta de estado en lugar de
     * crear avisos huérfanos. El work único se encola con
     * [ExistingWorkPolicy.KEEP]: si ya hay un reanálisis pendiente o en
     * curso para ese libro, WorkManager ignora la petición duplicada y el
     * libro nunca se procesa dos veces en paralelo; cuando el trabajo
     * anterior ya terminó, KEEP permite encolarlo de nuevo con normalidad.
     *
     * @return id del job de estado, o null si el libro no existe.
     */
    suspend fun enqueueReprocess(bookId: String): String? {
        val book = database.bookDao().getById(bookId) ?: return null
        val jobId = "reprocess_$bookId"
        ImportStatusStore.add(
            ImportJob(
                id = jobId,
                displayName = "Reanalizando «${book.title}»",
                state = ImportJobState.QUEUED,
                message = "En cola"
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
