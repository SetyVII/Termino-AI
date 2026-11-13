package com.termino.core.parser

/** Minimal text helpers, so this module does not depend on the RAG module. */
internal object TextUtils {
    private val whitespace = Regex("\\s+")

    fun collapseWhitespace(text: String): String = text.replace(whitespace, " ").trim()

    fun wordCount(text: String): Int = text.split(whitespace).count { it.isNotBlank() }
}
