# :core:rag — Búsqueda híbrida local (RAG sin servidores)

Indexa libros (o cualquier texto por capítulos) y recupera los pasajes más relevantes para una pregunta, **todo en el dispositivo**. Tiene su **propia base de datos Room** (`termino_rag.db`) y no conoce el esquema de la app: los libros se identifican con un `bookId` opaco.

## API

```kotlin
val engine = HybridRagEngine.create(context)

engine.indexBook(bookId, chapters = listOf(IndexChapter(0, "Capítulo 1", texto)))
val result = engine.search(
    bookId, query = "¿qué es la entropía?",
    topK = 5, currentChapterIndex = 2, currentVisibleText = paginaVisible,
    passageChars = 1200, neighborSources = 1
)
result.results.forEach { it.passage /* para el LLM */; it.excerpt /* para la UI */ }

engine.clearIndex(bookId) // al borrar el libro
```

## Pipeline

1. **Troceado** (`TextChunker`): ~350 palabras, 50 de solapamiento, por párrafos y oraciones.
2. **Léxico**: SQLite FTS4 + **BM25** (k1 1.5, b 0.75) con variantes singular/plural ES/EN.
3. **Vectorial** (`LocalEmbedder`): TF-IDF denso de 512 dimensiones con *hashing trick* (unigramas, bigramas, trigramas de caracteres), coseno.
4. **Fusión RRF** (k 60) + empujones suaves por capítulo actual y por página visible.
5. **MMR** para diversidad y descarte de casi-duplicados.
6. **Pasaje** más relevante del fragmento (`bestPassage`) y **vecinos** para las mejores fuentes.

## Por dónde mejorar

- Añadir un `Embedder` neuronal (ONNX/LiteRT, p. ej. EmbeddingGemma) como tercer canal en la misma fusión RRF; `LocalEmbedder` es el punto a abstraer.
- Reranking con LLM o cross-encoder sobre el *pool* de MMR.
- Empuje por sección (requiere guardar la sección en `RagChunkEntity`).

Notas de diseño y mediciones: [`docs/RAG_IMPROVEMENTS.md`](../../docs/RAG_IMPROVEMENTS.md).
