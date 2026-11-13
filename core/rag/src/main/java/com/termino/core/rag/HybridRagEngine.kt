package com.termino.core.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class IndexChapter(
    val chapterIndex: Int,
    val pageLabel: String,
    val text: String
)

data class RagResult(
    val chunk: RagChunkEntity,
    val score: Double,
    val lexicalRank: Int?,
    val vectorRank: Int?,
    /** Resumen corto para la UI (fuentes del chat). */
    val excerpt: String,
    /** Texto que recibe la IA: la parte más relevante del fragmento, con vecinos si aplica. */
    val passage: String = excerpt
) {
    val citation: String
        get() = chunk.pageLabel
}

data class RagSearchResult(
    val results: List<RagResult> = emptyList(),
    val queryTerms: List<String> = emptyList(),
    val lexicalCandidates: Int = 0,
    val vectorCandidates: Int = 0,
    val contextTerms: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = results.isEmpty()
}

/**
 * Hybrid retrieval: SQLite FTS4 (BM25) + local dense cosine vectors fused with
 * Reciprocal Rank Fusion.
 *
 * Retrieval-quality design notes:
 *  - RRF k stays at the literature-default 60: it flattens the head of both
 *    rank lists, which is exactly what you want when the lexical and vector
 *    channels disagree on the top hit but agree on "these 10 are relevant".
 *  - Weights stay near-balanced (1.0 lexical / 1.15 vector). BM25 owns exact
 *    terminology (names, rare words); the dense channel owns morphological
 *    and topical similarity. Both channels are comparably strong on this
 *    corpus, and post-fusion boosts (below) are multiplicative, so the
 *    balance matters more than small weight nudges.
 *  - Boosts are multiplicative, not additive: RRF scores are tiny (~1/60), so
 *    an additive constant would have to be scaled per-fusion and would
 *    dominate rank order. Multiplication preserves fused order and applies
 *    the same relative tilt to every candidate.
 *  - Boosts stay soft on purpose (chapter ~+10%, visible-text overlap ~+8%):
 *    enough to break ties toward what the reader is looking at, never enough
 *    to bury a globally better match from another chapter.
 *  - Book embeddings are cached in a per-book LRU for the whole session, so
 *    repeated searches skip both the BLOB read deserialisation and the
 *    ByteBuffer churn. Chunks indexed with an older embedder model are
 *    re-embedded lazily with the current model (and cached), keeping old
 *    books retrievable without a re-import.
 */
/**
 * Punto de entrada del módulo RAG. Uso típico:
 *
 * ```
 * val engine = HybridRagEngine.create(context)
 * engine.indexBook(bookId, chapters)            // al importar
 * val result = engine.search(bookId, pregunta)  // al preguntar
 * engine.clearIndex(bookId)                     // al borrar el libro
 * ```
 */
class HybridRagEngine(
    private val database: RagDatabase,
    private val embedder: LocalEmbedder = LocalEmbedder()
) {
    companion object {
        /** Crea el motor con su propia base de datos (termino_rag.db por defecto). */
        fun create(context: android.content.Context, databaseName: String = "termino_rag.db"): HybridRagEngine =
            HybridRagEngine(RagDatabase.build(context, databaseName))

        private const val RRF_K = 60.0
        private const val LEXICAL_WEIGHT = 1.0
        private const val VECTOR_WEIGHT = 1.15
        private const val CURRENT_CHAPTER_BOOST = 1.10
        private const val VISIBLE_TERM_BOOST_MAX = 0.08
        private const val CONTEXT_QUERY_WEIGHT = 0.35
        private const val CONTEXT_TERMS_LIMIT = 12
        private const val VISIBLE_TERM_SET_LIMIT = 400
        private const val CANDIDATE_LIMIT = 150
        private const val MAX_TOP_K = 12
        private const val BM25_K1 = 1.5
        private const val BM25_B = 0.75
        private const val MIN_COSINE = 0.015

        /** MMR: candidatos considerados, peso de relevancia y umbral de duplicado. */
        private const val MMR_POOL = 30
        private const val MMR_LAMBDA = 0.75
        private const val DUPLICATE_COSINE = 0.92

        /** Pasaje que recibe la IA por fuente y contexto extra para las mejores. */
        private const val PASSAGE_CHARS = 1500
        private const val NEIGHBOR_RESULTS = 2
        private const val NEIGHBOR_WORDS = 70

        /** Books kept in the session vector cache (LRU by bookId). */
        private const val CACHE_BOOKS = 2

        /** Vectors per book (6000 x 512 floats = ~12 MB worst case). */
        private const val CACHE_VECTORS_PER_BOOK = 6000
    }

    private data class ChunkDraft(
        val chapterIndex: Int,
        val chunkIndex: Int,
        val pageLabel: String,
        val content: String,
        val wordCount: Int
    )

    private data class Fused(
        val chunk: RagChunkEntity,
        var score: Double = 0.0,
        var lexicalRank: Int? = null,
        var vectorRank: Int? = null
    )

    /** Distinctive terms of the page the reader is looking at, if provided. */
    private class QueryContext(
        val terms: LinkedHashMap<String, Double> = LinkedHashMap(),
        val visibleTerms: Set<String> = emptySet()
    )

    private class SearchOutcome(
        val results: List<RagResult>,
        val vectorCandidates: Int
    )

    /**
     * Access-order LRU of per-book vector caches. Keyed by chunk id, which is
     * unique across books, so a cached vector can never leak across books.
     */
    private class BookVectors {
        val vectors = LinkedHashMap<Long, FloatArray>(64, 0.75f, true)

        fun get(chunkId: Long): FloatArray? = vectors[chunkId]

        fun put(chunkId: Long, vector: FloatArray, maxEntries: Int) {
            vectors[chunkId] = vector
            if (vectors.size > maxEntries) {
                val eldest = vectors.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                }
            }
        }
    }

    private val vectorCache = LinkedHashMap<String, BookVectors>(4, 0.75f, true)

    private fun bookVectors(bookId: String): BookVectors = synchronized(vectorCache) {
        vectorCache.getOrPut(bookId) { BookVectors() }
        if (vectorCache.size > CACHE_BOOKS) {
            val eldest = vectorCache.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        vectorCache.getValue(bookId)
    }

    private fun evictBookVectors(bookId: String) {
        synchronized(vectorCache) { vectorCache.remove(bookId) }
    }

    suspend fun indexBook(
        bookId: String,
        chapters: List<IndexChapter>,
        onProgress: (Int) -> Unit = {}
    ) {
        val ragDao = database.ragDao()
        clearIndex(bookId)
        if (chapters.isEmpty()) {
            onProgress(100)
            return
        }

        val drafts = mutableListOf<ChunkDraft>()
        chapters.forEach { chapter ->
            val chunks = TextChunker.chunk(chapter.text)
            chunks.forEachIndexed { index, content ->
                drafts.add(
                    ChunkDraft(
                        chapterIndex = chapter.chapterIndex,
                        chunkIndex = index,
                        pageLabel = chapter.pageLabel,
                        content = content,
                        wordCount = TextTokenizer.wordCount(content)
                    )
                )
            }
        }
        if (drafts.isEmpty()) {
            onProgress(100)
            return
        }

        onProgress(4)
        val documentFrequency = HashMap<String, Int>(drafts.size * 24)
        drafts.forEach { draft ->
            TextTokenizer.tokenizeUnique(draft.content).forEach { term ->
                documentFrequency[term] = (documentFrequency[term] ?: 0) + 1
            }
        }
        val documentCount = drafts.size
        val idf: (String) -> Double = { term ->
            TextTokenizer.idf(documentCount, documentFrequency[term] ?: 0)
        }

        val embedded = withContext(Dispatchers.Default) {
            drafts.mapIndexed { index, draft ->
                if (index % 12 == 0) {
                    onProgress(6 + (index * 58 / drafts.size.coerceAtLeast(1)))
                }
                draft to embedder.embed(draft.content, idf)
            }
        }
        onProgress(66)

        database.withTransaction {
            val entities = embedded.map { (draft, vector) ->
                RagChunkEntity(
                    bookId = bookId,
                    chapterIndex = draft.chapterIndex,
                    chunkIndex = draft.chunkIndex,
                    pageLabel = draft.pageLabel,
                    content = draft.content,
                    wordCount = draft.wordCount,
                    embedding = embedder.toBytes(vector),
                    embeddingDim = embedder.dimensions,
                    embeddingModel = LocalEmbedder.MODEL_NAME
                )
            }
            val ids = ragDao.insertChunks(entities)
            ids.chunked(400).forEach { idChunk ->
                val offset = ids.indexOf(idChunk.first())
                val rows = idChunk.mapIndexed { localIndex, id ->
                    val draft = drafts[offset + localIndex]
                    RagFtsEntity(
                        rowId = id,
                        content = draft.content,
                        bookId = bookId
                    )
                }
                ragDao.insertFts(rows)
            }
            val stats = documentFrequency.map { (term, frequency) ->
                RagTermStatEntity(bookId = bookId, term = term, docFreq = frequency)
            }
            stats.chunked(220).forEach { ragDao.insertTermStats(it) }
        }

        evictBookVectors(bookId)
        onProgress(100)
    }

    suspend fun clearIndex(bookId: String) {
        database.withTransaction {
            database.ragDao().deleteFtsByBook(bookId)
            database.ragDao().deleteTermStatsByBook(bookId)
            database.ragDao().deleteChunksByBook(bookId)
        }
        evictBookVectors(bookId)
    }

    /**
     * Hybrid search with optional reader context.
     *
     * @param currentVisibleText text currently on screen (or the chapter
     * fallback). When present, its distinctive terms (a) softly blend into the
     * query vector and (b) produce a soft lexical-overlap boost so chunks
     * that share vocabulary with what the reader sees surface earlier.
     */
    suspend fun search(
        bookId: String,
        query: String,
        topK: Int = 5,
        currentChapterIndex: Int? = null,
        currentVisibleText: String? = null,
        passageChars: Int = PASSAGE_CHARS,
        neighborSources: Int = NEIGHBOR_RESULTS
    ): RagSearchResult {
        if (query.isBlank()) return RagSearchResult()
        val ragDao = database.ragDao()
        val documentCount = ragDao.getChunkCount(bookId)
        if (documentCount == 0) return RagSearchResult()

        val terms = TextTokenizer.queryTerms(query)
        if (terms.isEmpty()) return RagSearchResult()
        val ftsTerms = TextTokenizer.expandForFts(terms)
        if (ftsTerms.isEmpty()) return RagSearchResult()
        val matchQuery = ftsTerms.joinToString(" OR ") { term ->
            "\"${TextTokenizer.escapeFtsTerm(term)}\""
        }

        val context = analyzeContext(currentVisibleText, ftsTerms.toSet())

        val lexicalCandidates = runCatching {
            ragDao.rawChunks(
                SimpleSQLiteQuery(
                    "SELECT c.* FROM rag_chunks c INNER JOIN rag_fts f ON f.rowid = c.id " +
                        "WHERE f.bookId = ? AND rag_fts MATCH ? LIMIT $CANDIDATE_LIMIT",
                    arrayOf(bookId, matchQuery)
                )
            )
        }.getOrDefault(emptyList())

        val averageLength = (ragDao.getAverageWordCount(bookId) ?: 1.0)
            .coerceAtLeast(1.0)
        val stats = ragDao.getTermStats(bookId, ftsTerms)
        val dfMap = stats.associate { it.term to it.docFreq }
        val idfProvider: (String) -> Double = { term ->
            TextTokenizer.idf(documentCount, dfMap[term] ?: 0)
        }

        val queryVector = withContext(Dispatchers.Default) {
            val base = embedder.embedQuery(query, idfProvider)
            if (context.terms.isEmpty()) {
                base
            } else {
                val contextVector = embedder.embedWeighted(context.terms, idfProvider)
                embedder.blend(base, contextVector, CONTEXT_QUERY_WEIGHT)
            }
        }

        // Only pay the big chunk fetch when the vector channel is usable.
        val allChunks = if (queryVector.all { it == 0f }) {
            emptyList()
        } else {
            ragDao.getAllChunks(bookId)
        }

        val outcome = withContext(Dispatchers.Default) {
            val countsByChunk = HashMap<Long, Map<String, Int>>()
            fun countsOf(chunk: RagChunkEntity): Map<String, Int> =
                countsByChunk.getOrPut(chunk.id) {
                    TextTokenizer.tokenize(chunk.content)
                        .groupingBy { it }
                        .eachCount()
                }

            val lexicalScored = lexicalCandidates.mapNotNull { chunk ->
                val counts = countsOf(chunk)
                var score = 0.0
                ftsTerms.forEach { term ->
                    val frequency = counts[term] ?: 0
                    if (frequency > 0) {
                        val df = dfMap[term] ?: 0
                        val idf = ln(1.0 + (documentCount - df + 0.5) / (df + 0.5))
                        val denominator = frequency + BM25_K1 * (
                            1.0 - BM25_B + BM25_B * (chunk.wordCount.toDouble() / averageLength)
                            )
                        score += idf * (frequency * (BM25_K1 + 1.0) / denominator)
                    }
                }
                if (score > 0.0) chunk to score else null
            }.sortedByDescending { it.second }.take(CANDIDATE_LIMIT)

            val lexicalRanked = if (lexicalScored.isEmpty() && lexicalCandidates.isNotEmpty()) {
                lexicalCandidates.mapIndexed { index, chunk ->
                    chunk to (1.0 / (index + 1).toDouble())
                }
            } else {
                lexicalScored
            }

            val vectorsById = HashMap<Long, FloatArray>()
            val vectorScored = if (allChunks.isEmpty()) {
                emptyList()
            } else {
                val bookCache = bookVectors(bookId)
                allChunks.mapNotNull { chunk ->
                    val vector = synchronized(bookCache) { bookCache.get(chunk.id) }
                        ?: run {
                            val computed =
                                if (chunk.embeddingModel == LocalEmbedder.MODEL_NAME) {
                                    embedder.fromBytes(chunk.embedding, chunk.embeddingDim)
                                } else {
                                    // Indexed with an older embedder: rebuild the
                                    // vector in the current space (same idf basis
                                    // as the query, so they stay comparable) and
                                    // cache it for the rest of the session.
                                    embedder.embed(chunk.content, idfProvider)
                                }
                            synchronized(bookCache) {
                                bookCache.put(chunk.id, computed, CACHE_VECTORS_PER_BOOK)
                            }
                            computed
                        }
                    vectorsById[chunk.id] = vector
                    val similarity = embedder.cosine(queryVector, vector)
                    if (similarity > MIN_COSINE) chunk to similarity else null
                }.sortedByDescending { it.second }.take(CANDIDATE_LIMIT)
            }

            val fused = LinkedHashMap<Long, Fused>()
            lexicalRanked.forEachIndexed { index, (chunk, _) ->
                val entry = fused.getOrPut(chunk.id) { Fused(chunk) }
                entry.lexicalRank = index + 1
                entry.score += LEXICAL_WEIGHT / (RRF_K + index + 1)
            }
            vectorScored.forEachIndexed { index, (chunk, _) ->
                val entry = fused.getOrPut(chunk.id) { Fused(chunk) }
                entry.vectorRank = index + 1
                entry.score += VECTOR_WEIGHT / (RRF_K + index + 1)
            }

            if (currentChapterIndex != null) {
                fused.values.forEach { entry ->
                    if (entry.chunk.chapterIndex == currentChapterIndex) {
                        entry.score *= CURRENT_CHAPTER_BOOST
                    }
                }
            }

            if (context.visibleTerms.isNotEmpty()) {
                val visibleSize = context.visibleTerms.size.toDouble()
                fused.values.forEach { entry ->
                    val counts = countsOf(entry.chunk)
                    if (counts.isEmpty()) return@forEach
                    var shared = 0
                    counts.keys.forEach { term ->
                        if (term in context.visibleTerms) shared++
                    }
                    if (shared > 0) {
                        val overlap = (shared.toDouble() / visibleSize).coerceIn(0.0, 1.0)
                        entry.score *= 1.0 + VISIBLE_TERM_BOOST_MAX * overlap
                    }
                }
            }

            // MMR: entre los mejores candidatos se prefieren los que aportan algo
            // nuevo, y se descartan casi-duplicados (p. ej. el solapamiento entre
            // fragmentos contiguos o pasajes repetidos en el libro).
            val pool = fused.values.sortedByDescending { it.score }.take(MMR_POOL)
            fun vectorOf(chunk: RagChunkEntity): FloatArray =
                vectorsById.getOrPut(chunk.id) { embedder.embed(chunk.content, idfProvider) }
            val selected = selectDiverse(pool, topK.coerceIn(1, MAX_TOP_K), ::vectorOf)

            val results = selected.map { entry ->
                RagResult(
                    chunk = entry.chunk,
                    score = entry.score,
                    lexicalRank = entry.lexicalRank,
                    vectorRank = entry.vectorRank,
                    excerpt = TextTokenizer.snippet(entry.chunk.content, 460),
                    passage = bestPassage(entry.chunk.content, ftsTerms, passageChars)
                )
            }
            SearchOutcome(results = results, vectorCandidates = vectorScored.size)
        }

        val withNeighbors = addNeighbors(bookId, outcome.results, neighborSources, passageChars)

        return RagSearchResult(
            results = withNeighbors,
            queryTerms = ftsTerms,
            lexicalCandidates = lexicalCandidates.size,
            vectorCandidates = outcome.vectorCandidates,
            contextTerms = context.terms.keys.toList()
        )
    }

    /**
     * Extracts the salient terms of the visible page for context blending
     * (top [CONTEXT_TERMS_LIMIT] by sublinear tf, excluding query terms) and a
     * wider top-term set for the lexical overlap boost.
     */
    private fun analyzeContext(currentVisibleText: String?, exclude: Set<String>): QueryContext {
        if (currentVisibleText.isNullOrBlank()) return QueryContext()
        val counts = TextTokenizer.tokenize(currentVisibleText)
            .groupingBy { it }
            .eachCount()
        if (counts.isEmpty()) return QueryContext()

        val ranked = counts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }
        )

        val contextTerms = LinkedHashMap<String, Double>(CONTEXT_TERMS_LIMIT)
        val visibleTerms = LinkedHashSet<String>(VISIBLE_TERM_SET_LIMIT)
        for (entry in ranked) {
            if (visibleTerms.size >= VISIBLE_TERM_SET_LIMIT) break
            if (entry.key in exclude) {
                // Query terms already drive retrieval; don't double-count them.
                visibleTerms.add(entry.key)
                continue
            }
            visibleTerms.add(entry.key)
            if (contextTerms.size < CONTEXT_TERMS_LIMIT) {
                contextTerms[entry.key] = 1.0 + ln(entry.value.toDouble())
            }
        }
        return QueryContext(terms = contextTerms, visibleTerms = visibleTerms)
    }

    suspend fun chunkCount(bookId: String): Int = database.ragDao().getChunkCount(bookId)

    private fun selectDiverse(
        pool: List<Fused>,
        k: Int,
        vectorOf: (RagChunkEntity) -> FloatArray
    ): List<Fused> {
        if (pool.size <= 1) return pool
        val maxScore = pool.first().score.takeIf { it > 0.0 } ?: return pool.take(k)
        val remaining = pool.toMutableList()
        val chosen = mutableListOf<Fused>()
        val chosenVectors = mutableListOf<FloatArray>()
        while (chosen.size < k && remaining.isNotEmpty()) {
            var best: Fused? = null
            var bestValue = Double.NEGATIVE_INFINITY
            var bestVector: FloatArray? = null
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                val vector = vectorOf(candidate.chunk)
                val redundancy = chosenVectors.maxOfOrNull { embedder.cosine(it, vector).toDouble() } ?: 0.0
                if (redundancy > DUPLICATE_COSINE) {
                    iterator.remove()
                    continue
                }
                val value = MMR_LAMBDA * (candidate.score / maxScore) - (1 - MMR_LAMBDA) * redundancy
                if (value > bestValue) {
                    bestValue = value
                    best = candidate
                    bestVector = vector
                }
            }
            val pick = best ?: break
            remaining.remove(pick)
            chosen += pick
            chosenVectors += bestVector!!
        }
        return chosen
    }

    /**
     * Para las mejores fuentes añade el final del fragmento anterior y el
     * principio del siguiente: muchas ideas empiezan o terminan justo en el
     * corte. Se salta el solapamiento de [TextChunker] para no repetir texto.
     */
    private suspend fun addNeighbors(
        bookId: String,
        results: List<RagResult>,
        neighborSources: Int,
        passageChars: Int
    ): List<RagResult> {
        if (results.isEmpty() || neighborSources <= 0) return results
        val dao = database.ragDao()
        return results.mapIndexed { index, result ->
            if (index >= neighborSources) return@mapIndexed result
            val chunk = result.chunk
            val neighbors = runCatching {
                dao.getChunksAt(bookId, chunk.chapterIndex, listOf(chunk.chunkIndex - 1, chunk.chunkIndex + 1))
            }.getOrDefault(emptyList())
            val prev = neighbors.firstOrNull { it.chunkIndex == chunk.chunkIndex - 1 }
            val next = neighbors.firstOrNull { it.chunkIndex == chunk.chunkIndex + 1 }
            val overlap = TextChunker.DEFAULT_OVERLAP_WORDS
            val before = prev?.content?.split(Regex("\\s+"))?.let { words ->
                val end = (words.size - overlap).coerceAtLeast(0)
                words.subList((end - NEIGHBOR_WORDS).coerceAtLeast(0), end).joinToString(" ")
            }.orEmpty()
            val after = next?.content?.split(Regex("\\s+"))?.let { words ->
                val start = overlap.coerceAtMost(words.size)
                words.subList(start, (start + NEIGHBOR_WORDS).coerceAtMost(words.size)).joinToString(" ")
            }.orEmpty()
            val core = bestPassage(chunk.content, emptyList(), (passageChars * 1.5).toInt()).let {
                // Con vecinos se envía el fragmento completo (hasta el límite) y no solo la ventana.
                if (before.isBlank() && after.isBlank()) result.passage else it
            }
            val passage = buildString {
                if (before.isNotBlank()) append("…").append(before).append("\n")
                append(core)
                if (after.isNotBlank()) append("\n").append(after).append("…")
            }
            result.copy(passage = passage)
        }
    }
}

/**
 * Ventana de ~[maxChars] del fragmento con más términos de la consulta,
 * alineada a frases. Antes solo se enviaban los primeros 460 caracteres,
 * aunque la respuesta estuviera al final del fragmento.
 */
internal fun bestPassage(content: String, terms: List<String>, maxChars: Int): String {
    val text = TextTokenizer.collapseWhitespace(content)
    if (text.length <= maxChars) return text
    val starts = mutableListOf(0)
    Regex("""(?<=[.!?…])\s+""").findAll(text).forEach { starts += it.range.last + 1 }
    val lowered = text.lowercase()
    val needles = terms.filter { it.length >= 3 }.distinct()
    var bestStart = 0
    var bestScore = -1
    for (start in starts) {
        if (start >= text.length) continue
        val end = (start + maxChars).coerceAtMost(text.length)
        val window = lowered.substring(start, end)
        val score = needles.sumOf { needle -> Regex(Regex.escape(needle)).findAll(window).count() }
        if (score > bestScore) {
            bestScore = score
            bestStart = start
        }
    }
    val end = (bestStart + maxChars).coerceAtMost(text.length)
    val cut = text.lastIndexOf(' ', end).takeIf { it > bestStart + maxChars / 2 && end < text.length } ?: end
    return (if (bestStart > 0) "…" else "") + text.substring(bestStart, cut).trim() +
        (if (cut < text.length) "…" else "")
}
