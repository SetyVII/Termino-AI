package com.termino.ui.screens

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.termino.TerminoApp
import com.termino.data.db.BookEntity
import com.termino.data.model.ImportJob
import com.termino.data.repo.BookRepository
import com.termino.data.repo.ImportStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val imports: List<ImportJob> = emptyList(),
    val query: String = "",
    val filteredBooks: List<BookEntity> = emptyList()
) {
    val isEmpty: Boolean get() = books.isEmpty() && imports.isEmpty()
}

class LibraryViewModel(
    private val bookRepository: BookRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    val ui: StateFlow<LibraryUiState> = combine(
        bookRepository.books,
        ImportStatusStore.jobs,
        query
    ) { books, imports, search ->
        val normalized = search.trim().lowercase()
        val filtered = if (normalized.isEmpty()) {
            books
        } else {
            books.filter { book ->
                book.title.lowercase().contains(normalized) ||
                    book.author.lowercase().contains(normalized)
            }
        }
        LibraryUiState(
            books = books,
            imports = imports,
            query = search,
            filteredBooks = filtered
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    fun setQuery(value: String) {
        query.value = value
    }

    fun importBooks(uris: List<Uri>) {
        uris.forEach { uri -> bookRepository.enqueueImport(uri, null) }
    }

    fun importSampleEpub() {
        bookRepository.enqueueSample(
            assetPath = "samples/muestra.epub",
            displayName = "La biblioteca de Asterión (muestra EPUB)"
        )
    }

    fun importSamplePdf() {
        bookRepository.enqueueSample(
            assetPath = "samples/muestra.pdf",
            displayName = "Leer con IA (muestra PDF)"
        )
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch { bookRepository.deleteBook(book.id) }
    }

    fun reprocessBook(book: BookEntity) {
        viewModelScope.launch { bookRepository.enqueueReprocess(book.id) }
    }

    fun dismissImport(id: String) {
        ImportStatusStore.remove(id)
    }

    fun clearFinishedImports() {
        ImportStatusStore.clearFinished()
    }

    companion object {
        fun factory(app: TerminoApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(app.serviceLocator.bookRepository)
            }
        }
    }
}
