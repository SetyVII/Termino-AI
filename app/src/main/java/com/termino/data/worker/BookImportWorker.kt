package com.termino.data.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.termino.R
import com.termino.TerminoApp
import com.termino.data.repo.ImportSource
import com.termino.data.repo.ImportStatusStore
import kotlinx.coroutines.CancellationException

class BookImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_JOB_ID = "job_id"
        const val KEY_URI = "uri"
        const val KEY_NAME = "name"
        const val KEY_ASSET = "asset"
        const val KEY_PROGRESS = "progress"
        const val KEY_MESSAGE = "message"
        const val KEY_BOOK_ID = "book_id"
        const val KEY_WARNING = "warning"
        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val uriString = inputData.getString(KEY_URI)
            ?: return failure(jobId, applicationContext.getString(R.string.import_error_no_file))
        val displayName = inputData.getString(KEY_NAME)
        val isAsset = inputData.getBoolean(KEY_ASSET, false)
        ImportStatusStore.progress(jobId, 0, applicationContext.getString(R.string.import_preparing))

        return try {
            val app = applicationContext as TerminoApp
            val importer = app.serviceLocator.bookImporter
            val source = if (isAsset) {
                ImportSource.Asset(uriString, displayName)
            } else {
                ImportSource.ContentUri(Uri.parse(uriString), displayName)
            }
            val result = importer.importBook(source) { progress, message ->
                ImportStatusStore.progress(jobId, progress, message)
            }
            ImportStatusStore.success(
                jobId, result.bookId, result.warning,
                applicationContext.getString(R.string.import_ready)
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
                    ?: applicationContext.getString(R.string.import_error_failed)
            )
        }
    }

    private fun failure(jobId: String, message: String): Result {
        ImportStatusStore.error(jobId, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }
}
