package com.termino.core.rag

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

object TextTokenizer {

    private val wordRegex = Regex("[\\p{L}\\p{N}]+")
    private val diacriticsRegex = Regex("\\p{M}+")
    private val whitespaceRegex = Regex("\\s+")
    private val inlineWhitespaceRegex = Regex("[^\\S\\n]+")
    private val paddedNewlineRegex = Regex(" ?\\n ?")

    private val stopWords = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "del", "se", "las", "por",
        "un", "para", "con", "no", "una", "su", "al", "lo", "como", "mas", "pero",
        "sus", "le", "ya", "o", "este", "si", "porque", "esta", "entre", "cuando",
        "muy", "sin", "sobre", "tambien", "me", "hasta", "hay", "donde", "quien",
        "desde", "todo", "nos", "durante", "todos", "uno", "les", "ni", "contra",
        "otros", "ese", "eso", "ante", "ellos", "e", "esto", "mi", "antes",
        "algunos", "que", "unos", "yo", "otro", "otras", "otra", "el", "tanto",
        "esa", "estos", "mucho", "quienes", "nada", "muchos", "cual", "poco",
        "ella", "estar", "estas", "algunas", "algo", "nosotros", "mis", "tu",
        "te", "ti", "tus", "ellas", "nosotras", "vosotros", "vosotras", "os",
        "mio", "mia", "mios", "mias", "tuyo", "tuya", "tuyos", "tuyas", "suyo",
        "suya", "suyos", "suyas", "nuestro", "nuestra", "nuestros", "nuestras",
        "vuestro", "vuestra", "vuestros", "vuestras", "esos", "esas", "estoy",
        "estas", "esta", "estamos", "estais", "estan", "ser", "son", "era",
        "fue", "han", "ha", "haber", "tiene", "tienen", "hace", "hacen", "puede",
        "pueden", "solo", "aun", "asi", "cada", "vez", "dos", "tres", "bien",
        "the", "and", "of", "to", "in", "a", "is", "that", "for", "it", "with",
        "as", "was", "on", "are", "be", "by", "this", "from", "or", "an", "at",
        "not", "but", "they", "you", "we", "his", "her", "its", "their", "our",
        "your", "i", "he", "she", "them", "these", "those", "has", "have", "had",
        "do", "does", "did", "will", "would", "can", "could", "should", "may",
        "might", "must", "about", "into", "over", "after", "before", "between",
        "under", "above", "than", "then", "so", "if", "because", "while", "where",
        "when", "who", "which", "what", "how", "all", "any", "both", "each",
        "few", "more", "most", "other", "some", "such", "only", "own", "same",
        "too", "very", "just", "now", "also", "there", "here", "been", "were"
    )

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return diacriticsRegex.replace(decomposed, "").lowercase(Locale.ROOT)
    }

    fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val normalized = normalize(text)
        return wordRegex.findAll(normalized)
            .map { it.value }
            .filter { token ->
                token.length >= 2 && token !in stopWords && token.any { it.isLetterOrDigit() }
            }
            .toList()
    }

    fun tokenizeUnique(text: String): Set<String> = tokenize(text).toSet()

    fun queryTerms(query: String): List<String> {
        val terms = tokenize(query).distinct().take(14)
        if (terms.isNotEmpty()) return terms
        // A query made only of stop words should still retrieve something.
        return wordRegex.findAll(normalize(query))
            .map { it.value }
            .filter { it.length >= 2 }
            .distinct()
            .take(8)
            .toList()
    }

    /**
     * Expands query terms with singular and plural variants so FTS recall
     * survives morphological mismatches ("capitulo" vs "capitulos", "city" vs
     * "cities"). Extra variants that match nothing are harmless for an OR
     * MATCH and simply score zero in BM25.
     */
    fun expandForFts(terms: List<String>): List<String> {
        val expanded = LinkedHashSet<String>()
        terms.forEach { term ->
            expanded.add(term)
            singular(term)?.let { expanded.add(it) }
            pluralCandidates(term).forEach { expanded.add(it) }
        }
        return expanded.take(24).toList()
    }

    private fun singular(term: String): String? = when {
        term.length > 5 && term.endsWith("ces") -> term.dropLast(3) + "z"
        term.length > 5 && term.endsWith("es") -> term.dropLast(2)
        term.length > 4 && term.endsWith("s") -> term.dropLast(1)
        else -> null
    }

    /**
     * Naive Spanish/English pluralisation. Terms are already normalised
     * (lowercase, diacritics stripped). Ambiguous consonant endings generate
     * both "-s" and "-es" because a wrong guess costs nothing in an OR query.
     */
    fun pluralCandidates(term: String): List<String> {
        if (term.length < 3 || term.length > 24) return emptyList()
        if (term.endsWith('s')) return emptyList()
        return when {
            term.endsWith("z") -> listOf(term.dropLast(1) + "ces")
            term.endsWith("x") || term.endsWith("ch") || term.endsWith("sh") ->
                listOf(term + "es")
            term.last() in "aeiou" -> listOf(term + "s")
            else -> listOf(term + "s", term + "es")
        }
    }

    fun idf(documentCount: Int, documentFrequency: Int): Double {
        val n = documentCount.coerceAtLeast(1).toDouble()
        val df = documentFrequency.coerceIn(0, documentCount).toDouble()
        return ln((n + 1.0) / (df + 1.0)) + 1.0
    }

    fun collapseWhitespace(text: String): String =
        whitespaceRegex.replace(text, " ").trim()

    /**
     * Collapses horizontal whitespace (spaces/tabs) but preserves newlines so
     * paragraph boundaries survive for structure-aware chunking. Consecutive
     * newlines are kept as-is: they are the paragraph separator.
     */
    fun collapseInlineWhitespace(text: String): String =
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(inlineWhitespaceRegex, " ")
            .replace(paddedNewlineRegex, "\n")
            .trim()

    fun snippet(text: String, maxChars: Int = 420): String {
        val collapsed = collapseWhitespace(text)
        if (collapsed.length <= maxChars) return collapsed
        val cut = collapsed.lastIndexOf(' ', maxChars)
        val end = if (cut > maxChars / 2) cut else maxChars
        return collapsed.substring(0, end).trimEnd() + "…"
    }

    fun wordCount(text: String): Int =
        text.split(whitespaceRegex).count { it.isNotBlank() }

    fun escapeFtsTerm(term: String): String =
        term.replace("\"", "").replace("*", "").trim()
}
