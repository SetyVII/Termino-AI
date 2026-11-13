package com.termino.core.rag

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Base de datos propia del índice RAG (fragmentos, FTS4 y estadísticas de
 * términos). Vive separada de la base de datos de la app para que el módulo
 * se pueda reutilizar sin acoplarse a su esquema.
 */
@Database(
    entities = [RagChunkEntity::class, RagFtsEntity::class, RagTermStatEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RagDatabase : RoomDatabase() {
    abstract fun ragDao(): RagDao

    companion object {
        fun build(context: Context, name: String = "termino_rag.db"): RagDatabase =
            Room.databaseBuilder(context.applicationContext, RagDatabase::class.java, name).build()
    }
}
