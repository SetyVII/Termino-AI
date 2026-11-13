package com.termino.core.rag

/**
 * Structure-aware chunker used by the RAG index.
 *
 * Strategy (per chapter, since the engine calls [chunk] once per chapter):
 * 1. If the text still has paragraph breaks (PDF text keeps them), pack whole
 *    paragraphs together up to [targetWords] so chunks never cut a paragraph
 *    in half. Oversized paragraphs fall back to sentence-boundary splitting.
 * 2. Otherwise (EPUB plain text is pre-collapsed to one line) split on word
 *    counts and back off to the nearest sentence-ending punctuation.
 * 3. Post-pass: chunks shorter than [MIN_MERGE_WORDS] words are merged with
 *    the next chunk of the same chapter so the BM25 length normalisation and
 *    the dense embeddings never see tiny, noisy fragments.
 */
object TextChunker {
    const val DEFAULT_TARGET_WORDS = 350
    const val DEFAULT_OVERLAP_WORDS = 50

    private const val MIN_MERGE_WORDS = 80
    private const val SENTENCE_LOOKBEHIND_WORDS = 45
    private val paragraphSeparator = Regex("\n{2,}")
    private val anyWhitespace = Regex("\\s+")

    fun chunk(
        text: String,
        targetWords: Int = DEFAULT_TARGET_WORDS,
        overlapWords: Int = DEFAULT_OVERLAP_WORDS
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val target = targetWords.coerceAtLeast(60)
        val overlap = overlapWords.coerceIn(0, target / 2)
        val maxMergedWords = target * 3 / 2

        val normalized = TextTokenizer.collapseInlineWhitespace(text)
        if (normalized.isBlank()) return emptyList()

        val paragraphs = normalized.split(paragraphSeparator)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val chunks = if (paragraphs.size >= 2) {
            packParagraphs(paragraphs, target, overlap)
        } else {
            splitBySentences(normalized, target, overlap)
        }
        return mergeShortChunks(chunks, MIN_MERGE_WORDS, maxMergedWords)
    }

    private fun packParagraphs(paragraphs: List<String>, target: Int, overlap: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0

        fun flush() {
            val text = current.toString().trim()
            if (text.isNotBlank()) chunks.add(text)
            current.setLength(0)
            currentWords = 0
        }

        paragraphs.forEach { paragraph ->
            val words = TextTokenizer.wordCount(paragraph)
            if (words > target) {
                flush()
                splitBySentences(paragraph, target, overlap).forEach(chunks::add)
                return@forEach
            }
            if (current.isNotEmpty() && currentWords + words > target) {
                val tail = if (overlap > 0) tailWords(current.toString().trim(), overlap) else ""
                flush()
                if (tail.isNotBlank()) {
                    current.append(tail)
                    currentWords = TextTokenizer.wordCount(tail)
                }
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(paragraph)
            currentWords += words
        }
        flush()
        return chunks
    }

    private fun splitBySentences(text: String, target: Int, overlap: Int): List<String> {
        val words = text.split(anyWhitespace).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        if (words.size <= target) return listOf(words.joinToString(" "))

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < words.size) {
            var end = (start + target).coerceAtMost(words.size)
            if (end < words.size) {
                val lowerBound = (end - SENTENCE_LOOKBEHIND_WORDS)
                    .coerceAtLeast(start + target / 2)
                for (candidate in end downTo lowerBound) {
                    val word = words[candidate - 1]
                    if (word.endsWith('.') || word.endsWith('!') || word.endsWith('?') ||
                        word.endsWith('…') || word.endsWith(':') || word.endsWith(';')
                    ) {
                        end = candidate
                        break
                    }
                }
            }
            val content = words.subList(start, end).joinToString(" ")
            if (content.isNotBlank()) chunks.add(content)
            if (end >= words.size) break
            val nextStart = end - overlap
            start = if (nextStart <= start) start + (target - overlap).coerceAtLeast(1) else nextStart
        }
        return chunks
    }

    /**
     * Merges chunks under [minWords] into the next chunk of the same chapter
     * (chunk() runs per chapter, so every merge here is intra-chapter). The
     * last chunk merges backwards into its predecessor when it is too short.
     */
    private fun mergeShortChunks(chunks: List<String>, minWords: Int, maxMergedWords: Int): List<String> {
        if (chunks.size < 2) return chunks
        val merged = mutableListOf<String>()
        var pending: String? = null
        var pendingWords = 0

        for (chunk in chunks) {
            val words = TextTokenizer.wordCount(chunk)
            val current = pending
            if (current == null) {
                pending = chunk
                pendingWords = words
                continue
            }
            if (pendingWords in 1 until minWords && pendingWords + words <= maxMergedWords) {
                pending = joinChunks(current, chunk)
                pendingWords += words
            } else {
                merged.add(current)
                pending = chunk
                pendingWords = words
            }
        }

        pending?.let { last ->
            if (last.isBlank()) return merged
            if (pendingWords in 1 until minWords && merged.isNotEmpty()) {
                val previous = merged.removeAt(merged.size - 1)
                if (TextTokenizer.wordCount(previous) + pendingWords <= maxMergedWords) {
                    merged.add(joinChunks(previous, last))
                } else {
                    merged.add(previous)
                    merged.add(last)
                }
            } else {
                merged.add(last)
            }
        }
        return merged
    }

    private fun joinChunks(first: String, second: String): String =
        if (first.endsWith('\n')) first + second else "$first\n$second"

    private fun tailWords(text: String, count: Int): String {
        if (count <= 0) return ""
        val words = text.split(anyWhitespace).filter { it.isNotBlank() }
        if (words.size <= count) return text
        return words.takeLast(count).joinToString(" ")
    }
}
