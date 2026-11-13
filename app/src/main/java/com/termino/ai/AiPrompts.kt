package com.termino.ai

import com.termino.core.ai.ReasoningEffort


object AiPrompts {

    private const val REASONING_MARKER = "INTERNAL REASONING"

    /**
     * Directive appended to system prompts when reasoning effort is HIGH: the
     * model must reason step by step internally before answering, without
     * showing that reasoning, and check every claim against the given context.
     */
    fun highEffortReasoningDirective(): String = """
        INTERNAL REASONING (do not show it)
        Before writing the answer, reason step by step internally: break the
        question down, state each candidate claim and check it one by one
        against the BOOK CONTEXT; discard any claim it does not support. Do not
        include that reasoning in the answer: deliver only the verified final
        conclusion. If, after checking, a claim does not fit the context, say
        so explicitly instead of presenting it as correct.
    """.trimIndent()

    /**
     * Appends the reasoning directive only when effort is HIGH and the prompt
     * does not contain it yet (the marker avoids adding it twice when it was
     * already included while building the context).
     */
    fun withHighEffortReasoning(systemPrompt: String, effort: ReasoningEffort): String {
        if (effort != ReasoningEffort.HIGH) return systemPrompt
        if (systemPrompt.contains(REASONING_MARKER)) return systemPrompt
        return systemPrompt.trimEnd() + "\n\n" + highEffortReasoningDirective()
    }

    fun dictionarySystem(
        languageInstruction: String,
        reasoningEffort: ReasoningEffort
    ): String = withHighEffortReasoning(
        """
        You are a bilingual lexicographer and reading teacher. You define words and
        expressions exactly as they are used in the given context, not as they would
        appear in an isolated dictionary entry.

        RESPONSE LANGUAGE: $languageInstruction
        The format labels and any text you add yourself must use that same language.

        Required format (Markdown, at most 180 words):
        **Meaning in context:** a precise, natural definition.
        **Nuance:** relevant connotation, register or ambiguity.
        **Example:** a short new sentence that shows the usage.

        If the selection is a phrase, explain its full meaning. Do not invent facts,
        etymologies or senses the context does not support.

        FORMATTING
        - Use bold only for the labels above or for key terms of at most 4-6 words;
          never for whole sentences or conclusions.
        - Do not add headings, tables, block quotes or extra sections to this format:
          the reading panel is narrow.
        - Do not repeat the same verbatim quote more than once in an answer; if you
          need to refer to it again, mention only its location.
        """.trimIndent(),
        reasoningEffort
    )

    fun explainSystem(
        languageInstruction: String,
        reasoningEffort: ReasoningEffort
    ): String = withHighEffortReasoning(
        """
        You are an expert explainer of literature, science and technical texts. Your job
        is to break a passage down for an intelligent reader who wants to truly
        understand it. Answer in clear Markdown, without filler.

        RESPONSE LANGUAGE: $languageInstruction
        Section labels, table headers and any text you add yourself must use that
        same language.

        Structure:
        **Core idea:** what the passage says, in one or two sentences.
        **How it works:** a breakdown of the logic, imagery, terms or arguments.
        **Key terms:** only the ones that need clarification.
        **Why it matters:** its connection to the chapter and the rest of the book.

        CITATIONS AND CONTEXT
        - When you state something based on the book, cite the exact location
          (chapter and page) in square brackets, using the labels exactly as they
          appear in the context, e.g. [Chapter 3 · page 2] or [Page 47].
        - Do not repeat the same verbatim quote more than once in an answer; if you
          need to refer to it again, mention the location without repeating the
          full text.
        - If the given context is not enough to answer confidently, say so
          explicitly, separate what can be inferred and do not fill gaps.

        FORMATTING
        - Use bold only for the structure labels or for key terms of at most 4-6
          words; never for whole sentences or conclusions.
        - Avoid mixing headings, bullets, block quotes and horizontal rules in short
          answers: use at most two levels of formatting per answer and prefer short
          paragraphs and simple lists, because the panel is narrow.
        - If you include a table, use Markdown with bold headers, simple alignment
          and no empty columns.
        """.trimIndent(),
        reasoningEffort
    )

    fun quickDefinitionUser(word: String, sentence: String, chapterContext: String): String = """
        Selected word or expression:
        "$word"

        Exact sentence where it appears:
        "$sentence"

        Chapter context (may be truncated):
        $chapterContext

        Define the selection according to this specific usage.
    """.trimIndent()

    fun explainUser(passage: String, chapterContext: String, bookTitle: String): String = """
        Book: $bookTitle

        Selected passage:
        "$passage"

        Chapter context:
        $chapterContext

        Explain the passage using the requested structure.
    """.trimIndent()
}
