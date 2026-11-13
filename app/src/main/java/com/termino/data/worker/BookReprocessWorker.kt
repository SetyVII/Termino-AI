package com.termino.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.termino.TerminoApp
import com.termino.data.repo.ImportStatusStore
import kotlinx.coroutines.CancellationException

/**
 * Reanaliza un libro ya importado: rehace capítulos, referencias, imágenes,
 * portada e índice RAG conservando subrayados y chat (mismo bookId).
 *
 * Entrada: [KEY_JOB_ID] (id del job en [ImportStatusStore]) y
 * [KEY_BOOK_ID] (identificador del libro a reanalizar).
 */
class BookReprocessWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_WARNING = "warning"
        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val bookId = inputData.getString(KEY_BOOK_ID)
            ?: return failure(jobId, "No se recibió el libro")
        ImportStatusStore.progress(jobId, 0, "Preparando reanálisis…")

        return try {
            val app = applicationContext as TerminoApp
            val importer = app.serviceLocator.bookImporter
            val result = importer.reprocessBook(bookId) { progress, message ->
                ImportStatusStore.progress(jobId, progress, message)
            }
            ImportStatusStore.success(jobId, result.bookId, result.warning, "Libro actualizado")
            Result.success(
                workDataOf(
                    KEY_BOOK_ID to result.bookId,
                    KEY_WARNING to result.warning
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure(
                jobId,
                error.message?.takeIf { it.isNotBlank() }
                    ?: "No se pudo reanalizar el libro"
            )
        }
    }

    private fun failure(jobId: String, message: String): Result {
        ImportStatusStore.error(jobId, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }
}
