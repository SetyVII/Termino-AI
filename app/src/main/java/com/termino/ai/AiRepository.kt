package com.termino.ai

import android.content.Context
import com.termino.R
import com.termino.core.ai.AiException
import com.termino.core.ai.AiMessage
import com.termino.core.ai.AiRequest
import com.termino.core.ai.AiStreamEvent
import com.termino.core.ai.AiStreamingClient
import com.termino.core.ai.ModelsResponse
import com.termino.core.ai.ReasoningEffort
import com.termino.data.prefs.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AiRepository(
    private val context: Context,
    private val client: AiStreamingClient
) {
    private companion object {
        const val REWRITE_TIMEOUT_MS = 5_000L
    }

    /**
     * The textual internal-reasoning directive is added whenever the setting
     * is MEDIUM or HIGH, alongside the API parameter: for models that do not
     * expose "reasoning_effort" it is the only way to request that effort.
     * withHighEffortReasoning only adds it for HIGH, so that value is forced
     * here; its marker prevents duplicates if the prompt already contained it.
     *
     * "reasoning_effort" itself is always sent unless the setting is OFF: if
     * the model does not accept it, the provider answers 400 and
     * AiStreamingClient retries without it, remembering that for the model.
     */
    private fun withReasoningDirective(systemPrompt: String, effort: ReasoningEffort): String =
        when (effort) {
            ReasoningEffort.OFF, ReasoningEffort.LOW -> systemPrompt
            ReasoningEffort.MEDIUM, ReasoningEffort.HIGH ->
                AiPrompts.withHighEffortReasoning(systemPrompt, ReasoningEffort.HIGH)
        }

    fun streamChat(
        settings: AppSettings,
        systemPrompt: String,
        history: List<AiMessage>,
        userMessage: String
    ): Flow<AiStreamEvent> {
        val messages = buildList {
            add(
                AiMessage(
                    "system",
                    withReasoningDirective(systemPrompt, settings.reasoningEffort)
                )
            )
            addAll(history)
            add(AiMessage("user", userMessage))
        }
        return client.stream(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            request = AiRequest(
                model = settings.chatModel,
                messages = messages,
                temperature = settings.temperature,
                maxTokens = settings.maxTokens,
                reasoningEffort = settings.reasoningEffort
            )
        )
    }

    fun streamDefinition(
        settings: AppSettings,
        word: String,
        sentence: String,
        chapterContext: String
    ): Flow<AiStreamEvent> = client.stream(
        baseUrl = settings.baseUrl,
        apiKey = settings.apiKey,
        request = AiRequest(
            model = settings.fastModel,
            messages = listOf(
                AiMessage(
                    "system",
                    withReasoningDirective(
                        AiPrompts.dictionarySystem(
                            settings.aiLanguage.instruction(),
                            settings.reasoningEffort
                        ),
                        settings.reasoningEffort
                    )
                ),
                AiMessage(
                    "user",
                    AiPrompts.quickDefinitionUser(word, sentence, chapterContext)
                )
            ),
            temperature = 0.2f,
            maxTokens = 700,
            reasoningEffort = settings.reasoningEffort
        )
    )

    fun streamExplanation(
        settings: AppSettings,
        passage: String,
        chapterContext: String,
        bookTitle: String
    ): Flow<AiStreamEvent> = client.stream(
        baseUrl = settings.baseUrl,
        apiKey = settings.apiKey,
        request = AiRequest(
            model = settings.chatModel,
            messages = listOf(
                AiMessage(
                    "system",
                    withReasoningDirective(
                        AiPrompts.explainSystem(
                            settings.aiLanguage.instruction(),
                            settings.reasoningEffort
                        ),
                        settings.reasoningEffort
                    )
                ),
                AiMessage(
                    "user",
                    AiPrompts.explainUser(passage, chapterContext, bookTitle)
                )
            ),
            temperature = 0.3f,
            maxTokens = 1200,
            reasoningEffort = settings.reasoningEffort
        )
    )

    /**
     * Real test of the current configuration (including unsaved changes from
     * Settings → Advanced): a minimal request to the chat model, and another
     * to the fast model if it differs.
     */
    suspend fun testConnection(settings: AppSettings): Result<String> = withContext(Dispatchers.IO) {
        if (settings.baseUrl.isBlank()) {
            return@withContext Result.failure(AiException(context.getString(R.string.ai_test_missing_url)))
        }
        if (settings.chatModel.isBlank()) {
            return@withContext Result.failure(AiException(context.getString(R.string.ai_test_missing_model)))
        }
        val models = listOf(settings.chatModel, settings.fastModel)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (model in models) {
            val ping = client.ping(settings.baseUrl, settings.apiKey, model)
            if (!ping.ok) {
                val reason = when (ping.httpCode) {
                    401, 403 -> context.getString(R.string.ai_test_key_rejected)
                    404 -> context.getString(R.string.ai_test_unknown_model, model)
                    null -> context.getString(R.string.ai_test_unreachable)
                    else -> "HTTP ${ping.httpCode}"
                }
                return@withContext Result.failure(
                    AiException(listOfNotNull(reason, ping.error?.take(160)).joinToString(" · "))
                )
            }
        }
        Result.success(context.getString(R.string.ai_test_ok, models.joinToString(" · ")))
    }

    /**
     * Turns a follow-up question ("and why is that?") into a self-contained
     * search query using the conversation and the fast model. It only serves
     * to retrieve better passages: the chat still receives the original
     * question. Returns null on any failure or timeout.
     */
    suspend fun rewriteQuery(
        settings: AppSettings,
        history: List<AiMessage>,
        question: String
    ): String? {
        val recent = history.takeLast(4)
        if (recent.isEmpty()) return null
        val model = settings.fastModel.ifBlank { settings.chatModel }
        if (model.isBlank()) return null
        val transcript = recent.joinToString("\n") { message ->
            val who = if (message.role == "user") "User" else "Assistant"
            "$who: ${message.content.take(600)}"
        }
        val answer = kotlinx.coroutines.withTimeoutOrNull(REWRITE_TIMEOUT_MS) {
            runCatching {
                client.complete(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    request = AiRequest(
                        model = model,
                        messages = listOf(
                            AiMessage(
                                "system",
                                "Rewrite the user's LAST question as a short, self-contained search query " +
                                    "for a book index. Resolve pronouns and references using the conversation. " +
                                    "Keep the original language. Output only the query, without quotes."
                            ),
                            AiMessage("user", "Conversation:\n$transcript\n\nLast question: $question")
                        ),
                        temperature = 0f,
                        maxTokens = 60,
                        reasoningEffort = ReasoningEffort.OFF
                    )
                )
            }.getOrNull()
        } ?: return null
        val cleaned = answer.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.trim('"', '«', '»', '\'')?.take(240)
        return cleaned?.takeIf { it.length >= 3 }
    }

    suspend fun fetchModels(settings: AppSettings): ModelsResponse =
        client.fetchModels(settings.baseUrl, settings.apiKey)

    /**
     * Models the provider exposes via GET {base}/models. Not cached: it is
     * queried every time Settings opens or the reload button is pressed, so
     * new provider models show up without updating the app. Returns an empty
     * list on any failure (empty URL, network, auth); never throws.
     */
    suspend fun listModels(settings: AppSettings): List<String> {
        if (settings.baseUrl.isBlank()) return emptyList()
        return client.listModels(baseUrl = settings.baseUrl, apiKey = settings.apiKey)
    }
}
