package com.termino.core.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fully local dense embedder.
 *
 * Hashing-trick vector space over three feature channels, all projected onto
 * the same [dimensions]-float vector and L2-normalised so retrieval is a plain
 * cosine similarity:
 *  - unigrams weighted with sublinear TF x corpus IDF (the TF-IDF core),
 *  - bigrams for phrase specificity ("red neuronal") at a reduced weight,
 *  - character trigrams (fastText style, with ^/$ boundary markers) as a low
 *    weight robustness channel: they survive typos, hyphenation and Spanish/
 *    English morphological variants that the unigram channel misses.
 *
 * Everything runs on-device: no model download, deterministic output, and
 * cheap enough for a tablet CPU (a few ms per chunk on Dispatchers.Default).
 */
class LocalEmbedder(
    val dimensions: Int = DEFAULT_DIMENSIONS
) {
    companion object {
        const val DEFAULT_DIMENSIONS = 512
        const val MODEL_NAME = "termino-hash-tfidf-512-v2"

        /**
         * Tuned inside the 0.45-0.6 band. 0.5 keeps bigrams as a strong
         * precision signal while leaving norm budget for the new trigram
         * channel without inflating hash collisions at 512 dims.
         */
        private const val BIGRAM_WEIGHT = 0.5

        /** Low on purpose: trigrams are a fuzzy fallback channel, not primary. */
        private const val TRIGRAM_WEIGHT = 0.22

        /** Tokens shorter than this add trigram noise without new signal. */
        private const val TRIGRAM_MIN_TOKEN_LENGTH = 4
    }

    fun embed(text: String, idfProvider: (String) -> Double): FloatArray {
        val tokens = TextTokenizer.tokenize(text)
        if (tokens.isEmpty()) return FloatArray(dimensions)

        val vector = FloatArray(dimensions)
        val unigramCounts = HashMap<String, Int>(tokens.size)
        tokens.forEach { token -> unigramCounts[token] = (unigramCounts[token] ?: 0) + 1 }

        unigramCounts.forEach { (term, count) ->
            val tf = 1.0 + ln(count.toDouble())
            val weight = tf * idfProvider(term)
            addFeature(vector, term, weight)
            spreadTrigrams(vector, term, weight)
        }

        if (tokens.size > 1) {
            val bigramCounts = HashMap<String, Int>(tokens.size)
            for (index in 0 until tokens.size - 1) {
                val key = tokens[index] + "\u0001" + tokens[index + 1]
                bigramCounts[key] = (bigramCounts[key] ?: 0) + 1
            }
            bigramCounts.forEach { (key, count) ->
                val separator = key.indexOf('\u0001')
                val idf = if (separator > 0) {
                    (idfProvider(key.substring(0, separator)) +
                        idfProvider(key.substring(separator + 1))) / 2.0
                } else {
                    1.0
                }
                val tf = 1.0 + ln(count.toDouble())
                addFeature(vector, key, tf * idf * BIGRAM_WEIGHT)
            }
        }

        normalize(vector)
        return vector
    }

    /**
     * Embeds an explicit bag of weighted terms (no adjacency information).
     * Used for the soft "current page" context expansion: the caller decides
     * per-term weights, so context terms can nudge the query without
     * drowning it. Unigram and trigram channels are included so the context
     * vector lives in the same space as [embed] output.
     */
    fun embedWeighted(
        weightedTerms: Map<String, Double>,
        idfProvider: (String) -> Double
    ): FloatArray {
        val vector = FloatArray(dimensions)
        if (weightedTerms.isEmpty()) return vector
        weightedTerms.forEach { (term, termWeight) ->
            if (termWeight <= 0.0) return@forEach
            val weight = termWeight * idfProvider(term)
            addFeature(vector, term, weight)
            spreadTrigrams(vector, term, weight)
        }
        normalize(vector)
        return vector
    }

    /**
     * Soft query expansion: `primary + w * secondary`, re-normalised. Both
     * inputs must be L2-normalised embedder outputs.
     */
    fun blend(primary: FloatArray, secondary: FloatArray, secondaryWeight: Double): FloatArray {
        if (secondary.isEmpty() || secondaryWeight <= 0.0) return primary
        var hasSignal = false
        for (value in secondary) {
            if (value != 0f) {
                hasSignal = true
                break
            }
        }
        if (!hasSignal) return primary
        val size = minOf(primary.size, secondary.size, dimensions)
        val blended = FloatArray(dimensions)
        for (index in 0 until size) {
            blended[index] = (primary[index] + secondary[index] * secondaryWeight).toFloat()
        }
        normalize(blended)
        return blended
    }

    fun embedQuery(query: String, idfProvider: (String) -> Double): FloatArray =
        embed(query, idfProvider)

    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val size = minOf(a.size, b.size)
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (index in 0 until size) {
            dot += a[index] * b[index]
            normA += a[index] * a[index]
            normB += b[index] * b[index]
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        vector.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray, dimensions: Int = this.dimensions): FloatArray {
        if (bytes.isEmpty()) return FloatArray(dimensions)
        val expected = dimensions * Float.SIZE_BYTES
        val usable = if (bytes.size >= expected) bytes.copyOf(expected) else bytes
        val buffer = ByteBuffer.wrap(usable).order(ByteOrder.LITTLE_ENDIAN)
        val vector = FloatArray(usable.size / Float.SIZE_BYTES)
        for (index in vector.indices) {
            vector[index] = buffer.float
        }
        return vector
    }

    /**
     * Spreads part of a token's weight across its character trigrams. The
     * token keeps its own unigram feature, so trigrams only add a soft
     * similarity surface for near-matches (typos, hyphenation, morphology).
     * Repeated trigrams inside one token accumulate, which is the desired
     * tf behaviour.
     */
    private fun spreadTrigrams(vector: FloatArray, token: String, tokenWeight: Double) {
        if (TRIGRAM_WEIGHT <= 0.0 || token.length < TRIGRAM_MIN_TOKEN_LENGTH) return
        val trigrams = charTrigrams(token)
        if (trigrams.isEmpty()) return
        val perTrigram = tokenWeight * TRIGRAM_WEIGHT / trigrams.size
        trigrams.forEach { trigram -> addFeature(vector, trigram, perTrigram) }
    }

    internal fun charTrigrams(token: String): List<String> {
        if (token.length < 2) return emptyList()
        val padded = "^$token\$"
        val result = ArrayList<String>(padded.length - 2)
        for (index in 0..padded.length - 3) {
            result.add(padded.substring(index, index + 3))
        }
        return result
    }

    private fun addFeature(vector: FloatArray, feature: String, weight: Double) {
        val hash = murmur3(feature)
        val index = (hash and 0x7fffffff) % dimensions
        val sign = if ((hash ushr 31) == 0) 1.0 else -1.0
        vector[index] += (weight * sign).toFloat()
    }

    private fun normalize(vector: FloatArray) {
        var sum = 0.0
        vector.forEach { value -> sum += value * value }
        if (sum <= 0.0) return
        val inverse = (1.0 / sqrt(sum)).toFloat()
        for (index in vector.indices) {
            vector[index] *= inverse
        }
    }

    private fun murmur3(value: String): Int {
        val data = value.toByteArray(Charsets.UTF_8)
        var hash = 0x9747b28c.toInt()
        val c1 = 0xcc9e2d51.toInt()
        val c2 = 0x1b873593
        var index = 0
        while (index + 4 <= data.size) {
            var k = (data[index].toInt() and 0xff) or
                ((data[index + 1].toInt() and 0xff) shl 8) or
                ((data[index + 2].toInt() and 0xff) shl 16) or
                ((data[index + 3].toInt() and 0xff) shl 24)
            k *= c1
            k = Integer.rotateLeft(k, 15)
            k *= c2
            hash = hash xor k
            hash = Integer.rotateLeft(hash, 13)
            hash = hash * 5 + 0xe6546b64.toInt()
            index += 4
        }
        var k = 0
        when (data.size - index) {
            3 -> {
                k = k xor ((data[index + 2].toInt() and 0xff) shl 16)
                k = k xor ((data[index + 1].toInt() and 0xff) shl 8)
                k = k xor (data[index].toInt() and 0xff)
            }
            2 -> {
                k = k xor ((data[index + 1].toInt() and 0xff) shl 8)
                k = k xor (data[index].toInt() and 0xff)
            }
            1 -> k = k xor (data[index].toInt() and 0xff)
        }
        if (k != 0) {
            k *= c1
            k = Integer.rotateLeft(k, 15)
            k *= c2
            hash = hash xor k
        }
        hash = hash xor data.size
        hash = hash xor (hash ushr 16)
        hash *= 0x85ebca6b.toInt()
        hash = hash xor (hash ushr 13)
        hash *= 0xc2b2ae35.toInt()
        hash = hash xor (hash ushr 16)
        return hash
    }
}
