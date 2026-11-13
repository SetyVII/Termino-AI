package com.termino.core.rag

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Fragmento indexado de un libro. [bookId] es un identificador opaco del
 * proyecto que usa el módulo: el índice no conoce la tabla de libros, así que
 * borrar un libro implica llamar a [HybridRagEngine.clearIndex].
 */
@Entity(
    tableName = "rag_chunks",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "chapterIndex"]),
        Index(value = ["bookId", "chapterIndex", "chunkIndex"], unique = true)
    ]
)
data class RagChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val chunkIndex: Int,
    val pageLabel: String,
    val content: String,
    val wordCount: Int,
    val embedding: ByteArray,
    val embeddingDim: Int,
    val embeddingModel: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RagChunkEntity) return false
        return id == other.id &&
            bookId == other.bookId &&
            chapterIndex == other.chapterIndex &&
            chunkIndex == other.chunkIndex &&
            pageLabel == other.pageLabel &&
            content == other.content &&
            wordCount == other.wordCount &&
            embedding.contentEquals(other.embedding) &&
            embeddingDim == other.embeddingDim &&
            embeddingModel == other.embeddingModel
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + bookId.hashCode()
        result = 31 * result + chapterIndex
        result = 31 * result + chunkIndex
        result = 31 * result + pageLabel.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + wordCount
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + embeddingDim
        result = 31 * result + embeddingModel.hashCode()
        return result
    }
}

/**
 * SQLite FTS4 table. The rowid is the same id as [RagChunkEntity] so a join is
 * an index lookup and deleting a book can remove lexical rows deterministically.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "rag_fts")
data class RagFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    val content: String,
    val bookId: String
)

@Entity(
    tableName = "rag_term_stats",
    primaryKeys = ["bookId", "term"],
    indices = [Index(value = ["bookId"])]
)
data class RagTermStatEntity(
    val bookId: String,
    val term: String,
    val docFreq: Int
)

data class CountRow(
    val value: Int
)

