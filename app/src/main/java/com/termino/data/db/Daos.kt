package com.termino.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity)

    @Update
    suspend fun update(book: BookEntity)

    @Query("UPDATE books SET lastOpenedAt = :time WHERE id = :id")
    suspend fun touch(id: String, time: Long)

    @Query(
        "UPDATE books SET lastChapterIndex = :chapterIndex, lastPageIndex = :pageIndex, " +
            "progress = :progress, lastOpenedAt = :time WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        chapterIndex: Int,
        pageIndex: Int,
        progress: Float,
        time: Long
    )

    @Query("UPDATE books SET ragIndexed = :indexed, ragError = :error WHERE id = :id")
    suspend fun updateRagState(id: String, indexed: Boolean, error: String?)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int
}

@Dao
interface ChapterDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getAll(bookId: String): List<ChapterEntity>

    @Query(
        "SELECT chapterIndex, title, pageLabel, wordCount FROM chapters " +
            "WHERE bookId = :bookId ORDER BY chapterIndex ASC"
    )
    suspend fun getSummaries(bookId: String): List<ChapterSummary>

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex LIMIT 1"
    )
    suspend fun getChapter(bookId: String, chapterIndex: Int): ChapterEntity?

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun count(bookId: String): Int

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    /** Chapters containing [query] (LIKE is case-insensitive for ASCII). */
    @Query(
        "SELECT chapterIndex, title, plainText FROM chapters " +
            "WHERE bookId = :bookId AND plainText LIKE '%' || :query || '%' " +
            "ORDER BY chapterIndex ASC"
    )
    suspend fun search(bookId: String, query: String): List<ChapterText>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY chapterIndex ASC, pageIndex ASC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    fun observeForBook(bookId: String): Flow<List<HighlightEntity>>

    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapterIndex ASC, startOffset ASC")
    suspend fun getForBook(bookId: String): List<HighlightEntity>

    @Query(
        "SELECT * FROM highlights WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startOffset ASC"
    )
    suspend fun getForChapter(bookId: String, chapterIndex: Int): List<HighlightEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: HighlightEntity)

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM highlights WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages WHERE bookId = :bookId ORDER BY createdAt ASC, id ASC")
    fun observeForBook(bookId: String): Flow<List<ChatMessageEntity>>

    @Query(
        "SELECT * FROM chat_messages WHERE bookId = :bookId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    suspend fun getRecent(bookId: String, limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}


@Dao
interface ReferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(references: List<BookReferenceEntity>)

    @Query(
        "SELECT * FROM book_references WHERE bookId = :bookId " +
            "ORDER BY chapterIndex ASC, referenceKey ASC"
    )
    suspend fun getForBook(bookId: String): List<BookReferenceEntity>

    @Query("DELETE FROM book_references WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}

