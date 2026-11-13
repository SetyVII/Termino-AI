package com.termino.core.parser

/** Utilidades de texto mínimas para no depender del módulo RAG. */
internal object TextUtils {
    private val whitespace = Regex("\\s+")

    fun collapseWhitespace(text: String): String = text.replace(whitespace, " ").trim()

    fun wordCount(text: String): Int = text.split(whitespace).count { it.isNotBlank() }
}
