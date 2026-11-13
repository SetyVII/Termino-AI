package com.termino.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.termino.R
import com.termino.TerminoApp
import com.termino.data.repo.ImportStatusStore
import kotlinx.coroutines.CancellationException

/**
 * Re-analyzes an already imported book: rebuilds chapters, references,
 * images, cover and RAG index while keeping highlights and chat (same bookId).
 *
 * Input: [KEY_JOB_ID] (job id in [ImportStatusStore]) and [KEY_BOOK_ID]
 * (the book to re-analyze).
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
            ?: return failure(jobId, applicationContext.getString(R.string.import_error_no_book))
        ImportStatusStore.progress(jobId, 0, applicationContext.getString(R.string.import_preparing_reprocess))

        return try {
            val app = applicationContext as TerminoApp
            val importer = app.serviceLocator.bookImporter
            val result = importer.reprocessBook(bookId) { progress, message ->
                ImportStatusStore.progress(jobId, progress, message)
            }
            ImportStatusStore.success(
                jobId, result.bookId, result.warning,
                applicationContext.getString(R.string.import_book_updated)
            )
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
                    ?: applicationContext.getString(R.string.import_error_reprocess_failed)
            )
        }
    }

    private fun failure(jobId: String, message: String): Result {
        ImportStatusStore.error(jobId, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }
}
