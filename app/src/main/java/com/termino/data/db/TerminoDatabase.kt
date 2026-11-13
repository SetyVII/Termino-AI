package com.termino.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App database: library, chapters, highlights, bookmarks, chat and
 * references. The RAG index lives separately, in the :core:rag module's own
 * database.
 *
 * Version 1 is the initial schema. Future changes must add a Migration
 * instead of destroying data.
 */
@Database(
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        HighlightEntity::class,
        ChatMessageEntity::class,
        BookReferenceEntity::class,
        BookmarkEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TerminoDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun highlightDao(): HighlightDao
    abstract fun chatDao(): ChatDao
    abstract fun referenceDao(): ReferenceDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        fun build(context: Context): TerminoDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                TerminoDatabase::class.java,
                "termino.db"
            ).build()
    }
}
