# RAG local de Término

Investigación de técnicas modernas de RAG local en móvil (2025–2026) y cómo se
aplicaron al pipeline híbrido de Término (FTS4/BM25 + vectores TF-IDF densos
512d + Reciprocal Rank Fusion + Room).

---

## 1. Investigación y qué aplica a este proyecto

### Chunking semántico / structure-aware (aplicado ✅)
La literatura 2025–2026 (guías de Weaviate, Databricks, benchmarks de Firecrawl
y Premai) converge en:
- El **chunking es la decisión de diseño nº 1** del pipeline: un corte mal puesto
  destruye contexto que ni el mejor reranker recupera.
- **Semantic chunking puro** (embeddings por frase + umbral de similitud) crea
  chunks demasiado pequeños y inconsistentes; los benchmarks recomiendan más
  bien *structure-aware*: respetar párrafos/oraciones con tamaño objetivo
  (~300–500 palabras) y solapamiento moderado (10–15 %).
- Chunks demasiado cortos duelen a la vez al BM25 (la normalización de longitud
  amplifica ruido) y a los embeddings (poca señal).

**Aplicado:** `TextChunker` ahora empaqueta párrafos enteros (el texto de PDF
conserva saltos de párrafo), cae a corte por oración para párrafos gigantes,
y funde chunks < 80 palabras con el siguiente dentro del mismo capítulo.

### Query expansion ligera sin LLM (aplicada ✅)
- PRF clásico (pseudo-relevance feedback) y expansión con LLM (HyDE, P-PRF)
  funcionan, pero añaden latencia y en móvil no hay presupuesto de inferencia
  extra. La expansión **léxica barata** (singular/plural, términos del contexto
  visible) captura la mayor parte del beneficio de recall.
- La expansión sube recall a costa de precisión; hay que aplicarla en el canal
  correcto: variantes morfológicas → MATCH léxico; términos de contexto →
  vector de consulta con peso reducido (blend), nunca al MATCH (inundaría la
  página actual).

**Aplicado:** `TextTokenizer.expandForFts` añade variantes plural (ES/EN);
`HybridRagEngine.search` acepta `currentVisibleText`, extrae sus 12 términos
más distintivos y los mezcla en el vector de consulta con peso 0.35
(`LocalEmbedder.embedWeighted` + `blend`).

### Reranking barato (aplicado ✅)
- Los cross-encoders (mxbai-rerank-v2, BGE-reranker) dominan los benchmarks
  pero son inviables offline en tablet; los LLM-rerankers añaden latencia.
- Los "rerankers baratos" de 2025–2026 son: **overlap léxico con el contexto
  visible**, boosts por proximidad estructural (capítulo actual) y fusión
  ponderada. En un lector, la relevancia *depende del punto de lectura*: es
  información de reranking gratuita que un RAG de corpus genérico no tiene.

**Aplicado:** boost multiplicativo suave (+8 % máx.) para chunks que comparten
términos con el texto visible actual, además del boost de capítulo (+10 %)
existente. Ambos multiplicativos: los scores RRF son diminutos (~1/60), un boost
aditivo tendría que escalarse por fusión y dominaría el orden; el multiplicativo
preserva el orden fusionado e inclina solo los empates.

### Tuning de hybrid search / RRF (revisado, documentado ✅)
- RRF con **k = 60** sigue siendo el estándar de facto (Elastic, Qdrant,
  pgvector): aplana la cabeza de ambas listas, exactamente lo que se quiere
  cuando BM25 y el canal denso discrepan del top-1 pero acuerdan el top-10.
- Pesos por canal: en corpus con mucha terminología exacta (nombres propios,
  notación matemática) BM25 aguanta pesos ≥ al denso; el consenso es
  near-balanced (1.0–1.2). Documentado en `HybridRagEngine`: se mantienen
  1.0 léxico / 1.15 vector, con candidatos 120 → 150 (mejor recall del top-5,
  coste marginal gracias al cache).

### Tuning de BM25 (revisado ✅)
- k1 = 1.5 y b = 0.75 son los valores estándar validados (Elastic "Practical
  BM25"): k1 = 1.5 da saturación razonable con chunks de ~350 palabras;
  b = 0.75 normaliza bien longitud con la media real del corpus (ya se
  consulta `getAverageWordCount`). No se cambian.

### Embeddings locales en Android (aplicado ✅)
- 2025 trajo EmbeddingGemma-308M y miniLM-like para on-device, pero requieren
  NNAPI/GPU delegation y ~300 MB. Para un lector offline, el hashing-trick
  TF-IDF (lo que ya usa Término) sigue siendo la opción correcta: determinista,
  sin descargas, y comparativamente fuerte en dominio cerrado (un solo libro).
- Mejora barata 2025: añadir **char n-grams (estilo fastText)** al hashing
  trick da robustez tipográfica y morfológica (typos, guionados, variantes
  ES/EN) casi gratis.

**Aplicado:** `LocalEmbedder` v2 mantiene 512d y sublinear TF + L2, con
bigramas a 0.5 (dentro de la banda probada 0.45–0.6) y un canal nuevo de
trigramas de caracteres (peso 0.22, tokens ≥ 4, con marcadores ^/$).

### Context caching (aplicado ✅)
- "Context engineering" 2025–2026: reutilizar cómputo de contexto entre
  invocaciones. En Término, cada búsqueda releía todos los BLOBs de
  embeddings del libro (`getAllChunks`) y re-parseaba 512 floats por chunk
  (ByteBuffer) — el coste dominante de la búsqueda.

**Aplicado:** LRU por `bookId` (2 libros, 6000 vectores/libro ≈ 12 MB) con
invalidación en `clearIndex`/`indexBook`. Los chunks indexados con el embedder
v1 se re-embedan perezosamente en el espacio v2 (misma base idf que la query)
y quedan cacheados: los libros viejos siguen siendo buscables sin re-import.

### Citas de fuentes / coherencia de numeración (aplicada ✅)
- La evaluación de citación 2025–2026 (Nature 2025, CiteME) muestra que el
  fallo nº 1 de citas es que el *resolutor* de la cita no coincide con lo que
  el usuario ve. La etiqueta debe venir de la numeración visible del libro,
  no de índices internos.

**Aplicado (corrección aparte):** `EpubProcessor.pageLabelFor` deriva el
`pageLabel` del número visible del título ("1. Introduction" → `Capítulo 1`,
"2.1 Background" → `Sección 2.1`), con la posición de contenido solo como
último recurso. El mismo `pageLabel` alimenta `ChapterEntity`,
`RagChunkEntity` y por tanto las citas de `ReadingContextBuilder`
(`[Fuente N · Capítulo 1]`, `Capítulo 1 · página 2 del capítulo`) y la UI.

### Imágenes EPUB / memoria del lector (aplicado ✅)
- Recomendación Android 2025 para lectores: normalizar assets en import
  (downscale con `inSampleSize`), no en cada render. JPEG q85 es el punto
  de dulce para fotos (calidad perceptual ≈ pérdida mínima); PNG para gráficos
  con líneas/texto.

**Aplicado:** `EpubProcessor.encodeImageAsset` re-escala rasters > 1600 px de
ancho (con tope de altura 4096 px), JPEG q85 para fotos (jpg/webp/bmp y PNG
> 512 KB), PNG para gráficos pequeños; SVG/GIF y pequeñas pasan intactas.

---

## 2. Cambios concretos por archivo

| Archivo | Cambios |
|---|---|
| `HybridRagEngine.kt` | `search` gana `currentVisibleText` opcional (API retrocompatible). Expansión de consulta con contexto visible (blend 0.35). Boost léxico suave por overlap con el texto visible (+8 %). LRU de embeddings por libro (2×6000, ~12 MB) invalidado en `clearIndex`. Re-embed perezoso de vectores con `embeddingModel` antiguo. Lecturas DB antes del bloque CPU; scoring/fusión en `Dispatchers.Default`. `RagSearchResult.contextTerms` (default) para observabilidad. Candidatos 120→150. Pesos RRF documentados (k=60, 1.0/1.15). |
| `LocalEmbedder.kt` | Modelo v2 (`readany-hash-tfidf-512-v2`). Bigramas 0.55→0.5. Canal nuevo de trigramas de caracteres (^tok$, peso 0.22). `embedWeighted` (bolsa de términos ponderados) y `blend` (mezcla L2). 512d, sublinear TF y normalización L2 intactos. |
| `TextChunker.kt` | Reescrito: empaquetado por párrafos (PDF), corte por oración como fallback, fusión de chunks < 80 palabras con el siguiente del mismo capítulo, solapamiento intacto (50). |
| `TextTokenizer.kt` | `pluralCandidates` (ES/EN) integrada en `expandForFts`. `collapseInlineWhitespace` (precompilado) preserva saltos de párrafo. |
| `EpubProcessor.kt` | `pageLabelFor`: numeración visible del libro ("Capítulo/Chapter N", "1. Introduction"→Capítulo 1, "2.1"→Sección 2.1; posición solo como último recurso). `encodeImageAsset` + `calculateInSampleSize`: downscale >1600 px, JPEG q85 fotos / PNG gráficos, passthrough de SVG/GIF/pequeñas. |
| `PdfProcessor.kt` | Regex de desguionado precompilado (antes se recreaba por página). Stripper reutilizado en todo el documento (confirmado/documentado). Aviso de página escaneada intacto. |
| `BookImporter.kt` | Libro+capítulos+referencias en **una transacción atómica** con inserts por lotes (200/250). Progreso granular: 34–38 imágenes (con contador), 39 capítulos, 41–98 indexación, 100. |

## 3. Impacto esperado

**Calidad de recuperación**
- Recall léxico ↑: variantes singular/plural cubren la morfología ES/EN más
  común sin coste de precisión (los términos que no matchean puntúan 0).
- Recall vectorial ↑: trigramas de caracteres recuperan coincidencias con
  typos, guiones y variantes morfológicas que los unigramas pierden.
- Precisión contextual ↑: el vector de consulta "anclado" a la página + boost
  de overlap priorizan lo que rodea al lector cuando es pertinente, sin
  secuestrar resultados globalmente mejores (boostes suaves y multiplicativos).
- Citas coherentes: la numeración de la barra de contexto, las fuentes del
  asistente y el índice del libro real por fin coinciden.
- Chunks mínimos de 80 palabras → menos fragmentos ruidosos en BM25 y en el
  contexto del LLM.

**Performance**
- Búsquedas repetidas en el mismo libro: se elimina el re-parseo de BLOBs
  (el mayor coste por búsqueda). Primera búsqueda igual; siguientes órdenes
  de magnitud menos CPU y cero churn de `ByteBuffer`.
- Import EPUB: imágenes enormes se decodifican a resolución de lectura
  (menos memoria y scroll más fluido en el lector), archivos más pequeños.
- Import PDF: menos GC/regex por página; el stripper ya era reutilizado.
- Import en general: una sola transacción para metadatos+capítulos+referencias
  (menos fsync y estado consistente ante fallos).

## 4. Trabajo futuro (fuera del alcance de esta pasada)
- DAO con proyección `rag_chunks` sin columna `embedding` para evitar leer
  BLOBs en `getAllChunks` (requiere tocar `Daos.kt`).
- Downscale de portadas (hoy solo assets internos).
- Índice de similitud aproximada (IVF/quantization) si algún libro supera
  ~20k chunks.
- Re-indexado automático de libros con `embeddingModel` v1 al abrirlos
  (hoy se resuelven perezosamente en memoria).

## 5. Prueba mental: "Large Language Models" (EPUB)
- Front matter sin números → `Capítulo N` posicional (sin cambio).
- Capítulo con título "1. Introduction", `chapterIndex = 8`:
  antes `pageLabel = "Capítulo 9"` (incoherente con el libro), ahora
  `leadingChapterNumber` captura "1" → `pageLabel = "Capítulo 1"` ✔.
- "2.1 Background" → `Sección 2.1` ✔. `chapterIndex` interno intacto (claves
  Room/RAG no cambian) ✔.
- Las citas del asistente (`[Fuente 1 · Capítulo 1]`, "Capítulo 1 · página 2
  del capítulo") y la barra de contexto usan el mismo `pageLabel` ✔.


---

## Mejoras posteriores (v1.0)

- **Pasaje relevante**: a la IA se envía la ventana del fragmento con más
  términos de la consulta (alineada a frases), no solo su comienzo.
- **Vecinos**: las mejores fuentes incluyen el final del fragmento anterior y el
  principio del siguiente (sin repetir el solapamiento del chunker).
- **MMR**: entre los 30 mejores candidatos se descartan casi-duplicados
  (coseno > 0.92) y se equilibra relevancia (λ = 0.75) con diversidad.
- **Reescritura de seguimiento**: preguntas como «¿y eso por qué?» se
  reescriben con el modelo rápido antes de buscar (timeout 5 s, con fallback).
- **Citas por sección**: cada fuente indica la sección del índice jerárquico.
- **Presupuesto de contexto** (Ajustes → Respuestas de la IA): Compacto para
  modelos locales o gratuitos, Equilibrado o Amplio; Automático elige según el
  servidor.
