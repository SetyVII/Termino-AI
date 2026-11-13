package com.termino.core.rag

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Dedicated database for the RAG index (chunks, FTS4 and term statistics).
 * It lives apart from the app database so the module can be reused without
 * coupling to the app schema.
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
