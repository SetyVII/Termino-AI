package com.termino.data.repo

import com.termino.core.reader.HighlightColor
import com.termino.data.db.HighlightDao
import com.termino.data.db.HighlightEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class HighlightRepository(
    private val dao: HighlightDao
) {
    fun observeForBook(bookId: String): Flow<List<HighlightEntity>> = dao.observeForBook(bookId)

    suspend fun getForBook(bookId: String): List<HighlightEntity> = dao.getForBook(bookId)

    suspend fun getForChapter(bookId: String, chapterIndex: Int): List<HighlightEntity> =
        dao.getForChapter(bookId, chapterIndex)

    suspend fun add(
        bookId: String,
        chapterIndex: Int,
        pageLabel: String,
        startOffset: Int,
        endOffset: Int,
        selectedText: String,
        color: HighlightColor,
        note: String? = null
    ): HighlightEntity {
        val highlight = HighlightEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = chapterIndex,
            pageLabel = pageLabel,
            startOffset = startOffset,
            endOffset = endOffset,
            selectedText = selectedText,
            color = color.key,
            note = note,
            createdAt = System.currentTimeMillis()
        )
        dao.insert(highlight)
        return highlight
    }

    suspend fun delete(id: String) = dao.deleteById(id)

    suspend fun deleteByBook(bookId: String) = dao.deleteByBook(bookId)
}
