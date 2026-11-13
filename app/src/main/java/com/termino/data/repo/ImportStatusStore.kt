package com.termino.data.repo

import com.termino.data.model.ImportJob
import com.termino.data.model.ImportJobState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object ImportStatusStore {
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs.asStateFlow()

    fun add(job: ImportJob) {
        _jobs.update { current ->
            // If an active job with this id already exists, add() (e.g. an
            // import retry) must not reset it to 0/QUEUED: it keeps its
            // progress, state and message and only refreshes the display
            // name. It is replaced only if the previous job finished
            // (SUCCESS/ERROR) or does not exist.
            val effective = current.firstOrNull { it.id == job.id }
                ?.takeIf { existing ->
                    existing.state == ImportJobState.QUEUED ||
                        existing.state == ImportJobState.RUNNING
                }
                ?.let { active -> active.copy(displayName = job.displayName) }
                ?: job
            (listOf(effective) + current.filterNot { it.id == job.id }).take(8)
        }
    }

    fun progress(id: String, progress: Int, message: String? = null) {
        _jobs.update { current ->
            current.map { job ->
                if (job.id == id) {
                    job.copy(
                        progress = progress.coerceIn(0, 100),
                        state = ImportJobState.RUNNING,
                        message = message
                    )
                } else {
                    job
                }
            }
        }
    }

    fun success(
        id: String,
        bookId: String,
        warning: String?,
        successMessage: String
    ) {
        _jobs.update { current ->
            current.map { job ->
                if (job.id == id) {
                    job.copy(
                        progress = 100,
                        state = ImportJobState.SUCCESS,
                        message = warning ?: successMessage,
                        bookId = bookId
                    )
                } else {
                    job
                }
            }
        }
    }

    fun error(id: String, message: String) {
        _jobs.update { current ->
            current.map { job ->
                if (job.id == id) {
                    job.copy(state = ImportJobState.ERROR, message = message)
                } else {
                    job
                }
            }
        }
    }

    fun remove(id: String) {
        _jobs.update { current -> current.filterNot { it.id == id } }
    }

    fun clearFinished() {
        _jobs.update { current ->
            current.filterNot {
                it.state == ImportJobState.SUCCESS || it.state == ImportJobState.ERROR
            }
        }
    }
}
