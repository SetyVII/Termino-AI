package com.termino

import com.termino.core.ai.AiStreamingClient
import com.termino.core.ai.ProviderVerifier
import com.termino.core.parser.EpubProcessor
import com.termino.core.parser.PdfProcessor
import com.termino.core.rag.HybridRagEngine
import android.content.Context
import com.termino.ai.AiRepository
import com.termino.ai.ReadingContextBuilder
import com.termino.data.db.TerminoDatabase
import com.termino.data.prefs.SettingsRepository
import com.termino.data.repo.BookImporter
import com.termino.data.repo.BookRepository
import com.termino.data.repo.ChatRepository
import com.termino.data.repo.HighlightRepository

class ServiceLocator(context: Context) {
    private val appContext = context.applicationContext

    val database: TerminoDatabase by lazy { TerminoDatabase.build(appContext) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val fontRepository: com.termino.data.fonts.FontRepository by lazy {
        com.termino.data.fonts.FontRepository(appContext)
    }
    val aiStreamingClient: AiStreamingClient by lazy { AiStreamingClient() }
    val aiRepository: AiRepository by lazy { AiRepository(aiStreamingClient) }
    val providerVerifier: ProviderVerifier by lazy { ProviderVerifier(aiStreamingClient) }
    val ragEngine: HybridRagEngine by lazy { HybridRagEngine.create(appContext) }
    val epubProcessor: EpubProcessor by lazy { EpubProcessor() }
    val pdfProcessor: PdfProcessor by lazy { PdfProcessor(appContext) }
    val bookRepository: BookRepository by lazy {
        BookRepository(appContext, database, ragEngine)
    }
    val highlightRepository: HighlightRepository by lazy {
        HighlightRepository(database.highlightDao())
    }
    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }
    val readingContextBuilder: ReadingContextBuilder by lazy { ReadingContextBuilder() }
    val bookImporter: BookImporter by lazy {
        BookImporter(
            context = appContext,
            database = database,
            epubProcessor = epubProcessor,
            pdfProcessor = pdfProcessor,
            ragEngine = ragEngine
        )
    }
}
