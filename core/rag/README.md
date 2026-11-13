# :core:rag — Local hybrid search (serverless RAG)

Indexes books (or any chapter-based text) and retrieves the passages most relevant to a question, **entirely on the device**. It has its **own Room database** (`termino_rag.db`) and does not know the app schema: books are identified by an opaque `bookId`.

## API

```kotlin
val engine = HybridRagEngine.create(context)

engine.indexBook(bookId, chapters = listOf(IndexChapter(0, "Chapter 1", text)))
val result = engine.search(
    bookId, query = "what is entropy?",
    topK = 5, currentChapterIndex = 2, currentVisibleText = visiblePage,
    passageChars = 1200, neighborSources = 1
)
result.results.forEach { it.passage /* for the LLM */; it.excerpt /* for the UI */ }

engine.clearIndex(bookId) // when the book is deleted
```

## Pipeline

1. **Chunking** (`TextChunker`): ~350 words with 50 words of overlap, packed by paragraph and sentence; chunks under 80 words are merged with the next one in the same chapter.
2. **Lexical**: SQLite FTS4 + **BM25** (k1 1.5, b 0.75), with English/Spanish singular/plural query expansion.
3. **Vector** (`LocalEmbedder`): 512-dimension dense TF-IDF using the *hashing trick* (unigrams, bigrams, character trigrams), cosine similarity. Deterministic, no model download.
4. **RRF fusion** (k 60, weights 1.0 lexical / 1.15 vector) plus soft multiplicative boosts for the current chapter (~+10%) and overlap with the visible page (~+8%). The visible page's distinctive terms are also blended into the query vector.
5. **MMR** for diversity, dropping near-duplicates (cosine > 0.92, λ = 0.75).
6. The most relevant **passage** of each chunk (`bestPassage`), plus **neighbors** (end of the previous chunk, start of the next) for the top sources.

Book vectors are cached in a per-book LRU (2 books, ~12 MB) for the session. Chunks indexed with an older embedder version are re-embedded lazily, so old books stay searchable without re-importing.

## Possible improvements

- A neural `Embedder` (ONNX/LiteRT, e.g. EmbeddingGemma) as a third channel in the same RRF fusion; `LocalEmbedder` is the point to abstract.
- LLM or cross-encoder reranking over the MMR pool.
- Section boost (requires storing the section in `RagChunkEntity`).
- A DAO projection without the `embedding` column to avoid reading BLOBs when only metadata is needed.
