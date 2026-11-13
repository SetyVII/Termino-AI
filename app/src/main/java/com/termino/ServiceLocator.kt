package com.termino

import com.termino.core.ai.AiStreamingClient
import com.termino.core.ai.ProviderVerifier
import com.termino.core.parser.EpubProcessor
import com.termino.core.parser.ParserLabels
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
    val aiRepository: AiRepository by lazy { AiRepository(appContext, aiStreamingClient) }
    val providerVerifier: ProviderVerifier by lazy { ProviderVerifier(aiStreamingClient) }
    val ragEngine: HybridRagEngine by lazy { HybridRagEngine.create(appContext) }
    private val parserLabels: ParserLabels by lazy {
        ParserLabels(
            chapter = appContext.getString(R.string.parser_chapter),
            section = appContext.getString(R.string.parser_section),
            sectionReference = appContext.getString(R.string.parser_section_reference),
            page = appContext.getString(R.string.parser_page),
            affiliation = appContext.getString(R.string.parser_affiliation),
            contact = appContext.getString(R.string.parser_contact),
            figure = appContext.getString(R.string.parser_figure),
            table = appContext.getString(R.string.parser_table),
            bibliography = appContext.getString(R.string.parser_bibliography),
            untitledBook = appContext.getString(R.string.parser_untitled_book),
            untitledPdf = appContext.getString(R.string.parser_untitled_pdf),
            unknownAuthor = appContext.getString(R.string.parser_unknown_author),
            emptyPdfPage = appContext.getString(R.string.parser_empty_pdf_page)
        )
    }
    val epubProcessor: EpubProcessor by lazy { EpubProcessor(parserLabels) }
    val pdfProcessor: PdfProcessor by lazy { PdfProcessor(appContext, parserLabels) }
    val bookRepository: BookRepository by lazy {
        BookRepository(appContext, database, ragEngine)
    }
    val highlightRepository: HighlightRepository by lazy {
        HighlightRepository(database.highlightDao())
    }
    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }
    val readingContextBuilder: ReadingContextBuilder by lazy { ReadingContextBuilder(appContext) }
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
