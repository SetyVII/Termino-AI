package com.termino.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val format: String,
    val filePath: String,
    val coverPath: String?,
    val description: String?,
    val language: String?,
    val totalChapters: Int,
    val totalPages: Int,
    val totalWords: Int,
    val addedAt: Long,
    val lastOpenedAt: Long,
    val lastChapterIndex: Int,
    val lastPageIndex: Int,
    val progress: Float,
    val ragIndexed: Boolean,
    val ragError: String?
)

@Entity(
    tableName = "chapters",
    indices = [
        Index(value = ["bookId", "chapterIndex"], unique = true),
        Index(value = ["bookId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val chapterIndex: Int,
    val title: String,
    val pageLabel: String,
    val html: String,
    val plainText: String,
    val wordCount: Int
)

@Entity(
    tableName = "highlights",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "chapterIndex"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val pageLabel: String,
    val startOffset: Int,
    val endOffset: Int,
    val selectedText: String,
    val color: String,
    val note: String?,
    val createdAt: Long
)

@Entity(
    tableName = "chat_messages",
    indices = [
        Index(value = ["bookId"]),
        Index(value = ["bookId", "createdAt"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val role: String,
    val content: String,
    val sourcesJson: String?,
    val model: String?,
    val isError: Boolean,
    val createdAt: Long
)


@Entity(
    tableName = "book_references",
    primaryKeys = ["bookId", "referenceKey"],
    indices = [Index(value = ["bookId"])],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookReferenceEntity(
    val bookId: String,
    val referenceKey: String,
    val numericKey: String?,
    val label: String,
    val content: String,
    val chapterIndex: Int,
    val anchor: String?
)

data class ChapterSummary(
    val chapterIndex: Int,
    val title: String,
    val pageLabel: String,
    val wordCount: Int
)

/** Marcador de página. [excerpt] es el inicio del texto visible: permite
 * volver al punto exacto aunque cambie el tamaño de letra (la página cambia). */
@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["bookId"])],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterIndex: Int,
    val pageIndex: Int,
    val excerpt: String,
    val createdAt: Long
)

/** Texto plano de un capítulo para la búsqueda dentro del libro. */
data class ChapterText(
    val chapterIndex: Int,
    val title: String,
    val plainText: String
)
