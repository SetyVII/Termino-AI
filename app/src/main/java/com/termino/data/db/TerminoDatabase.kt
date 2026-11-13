package com.termino.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos de la app: biblioteca, capítulos, subrayados, marcadores,
 * chat y referencias. El índice RAG vive aparte, en la base de datos propia
 * del módulo :core:rag.
 *
 * Versión 1 de Termino: esquema inicial, sin migraciones heredadas. Los
 * cambios futuros deben añadir una Migration en lugar de destruir datos.
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
