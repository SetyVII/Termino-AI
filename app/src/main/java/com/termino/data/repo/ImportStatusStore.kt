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
            // Si ya existe un job activo con ese id, un add() (p. ej. un
            // re-intento de importación) no debe resetearlo a 0/QUEUED:
            // conserva su progress, state y message y solo refresca el
            // nombre visible. Solo se reemplaza si el job anterior ya
            // terminó (SUCCESS/ERROR) o no existe.
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
        warning: String? = null,
        successMessage: String? = null
    ) {
        _jobs.update { current ->
            current.map { job ->
                if (job.id == id) {
                    job.copy(
                        progress = 100,
                        state = ImportJobState.SUCCESS,
                        message = warning ?: successMessage ?: "Listo para leer",
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
