package com.termino.core.rag

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface RagDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(chunks: List<RagChunkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFts(rows: List<RagFtsEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTermStats(rows: List<RagTermStatEntity>)

    @Query("SELECT COUNT(*) FROM rag_chunks WHERE bookId = :bookId")
    suspend fun getChunkCount(bookId: String): Int

    @Query("SELECT AVG(wordCount) FROM rag_chunks WHERE bookId = :bookId")
    suspend fun getAverageWordCount(bookId: String): Double?

    @Query("SELECT * FROM rag_chunks WHERE bookId = :bookId ORDER BY chapterIndex ASC, chunkIndex ASC")
    suspend fun getAllChunks(bookId: String): List<RagChunkEntity>

    @Query("SELECT * FROM rag_chunks WHERE id = :id LIMIT 1")
    suspend fun getChunkById(id: Long): RagChunkEntity?

    @Query(
        "SELECT * FROM rag_chunks WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "AND chunkIndex IN (:chunkIndexes)"
    )
    suspend fun getChunksAt(bookId: String, chapterIndex: Int, chunkIndexes: List<Int>): List<RagChunkEntity>

    @Query(
        "SELECT * FROM rag_term_stats WHERE bookId = :bookId AND term IN (:terms)"
    )
    suspend fun getTermStats(bookId: String, terms: List<String>): List<RagTermStatEntity>

    @RawQuery
    suspend fun rawChunks(query: SupportSQLiteQuery): List<RagChunkEntity>

    @RawQuery
    suspend fun rawCount(query: SupportSQLiteQuery): List<CountRow>

    @Query("DELETE FROM rag_fts WHERE rowid IN (SELECT id FROM rag_chunks WHERE bookId = :bookId)")
    suspend fun deleteFtsByBook(bookId: String)

    @Query("DELETE FROM rag_term_stats WHERE bookId = :bookId")
    suspend fun deleteTermStatsByBook(bookId: String)

    @Query("DELETE FROM rag_chunks WHERE bookId = :bookId")
    suspend fun deleteChunksByBook(bookId: String)
}
