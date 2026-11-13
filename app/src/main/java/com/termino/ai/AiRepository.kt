package com.termino.ai

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
    private val client: AiStreamingClient
) {
    private companion object {
        const val REWRITE_TIMEOUT_MS = 5_000L
    }

    /**
     * "reasoning_effort" se envía siempre que el ajuste no esté apagado: si
     * el modelo no lo admite, el proveedor responde 400, AiStreamingClient
     * reintenta sin él y recuerda la respuesta para ese modelo. Así no hace
     * falta mantener una lista de nombres de modelos de razonamiento.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun apiReasoningEffort(model: String, effort: ReasoningEffort): ReasoningEffort = effort

    /**
     * La directiva textual de razonamiento interno se aplica SIEMPRE que el
     * ajuste esté en MEDIUM o HIGH, con o sin el parámetro de API: para los
     * modelos que no exponen "reasoning_effort" es la única vía de pedir ese
     * esfuerzo, y para los que sí lo exponen resulta idempotente.
     * withHighEffortReasoning solo la añade con HIGH, así que se fuerza ese
     * valor; su marca interna evita duplicados si el prompt ya la contenía
     * (p. ej. cuando ReadingContextBuilder o los prompts de AiPrompts la
     * incluyeron antes).
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
                reasoningEffort = apiReasoningEffort(
                    settings.chatModel,
                    settings.reasoningEffort
                )
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
            reasoningEffort = apiReasoningEffort(settings.fastModel, settings.reasoningEffort)
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
            reasoningEffort = apiReasoningEffort(settings.chatModel, settings.reasoningEffort)
        )
    )

    /**
     * Prueba real de la configuración actual (incluidos los cambios sin
     * guardar de Ajustes → Avanzado): una petición mínima al modelo de chat
     * y otra al rápido si es distinto.
     */
    suspend fun testConnection(settings: AppSettings): Result<String> = withContext(Dispatchers.IO) {
        if (settings.baseUrl.isBlank()) {
            return@withContext Result.failure(AiException("Escribe una URL base"))
        }
        if (settings.chatModel.isBlank()) {
            return@withContext Result.failure(AiException("Elige un modelo de chat"))
        }
        val models = listOf(settings.chatModel, settings.fastModel)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        for (model in models) {
            val ping = client.ping(settings.baseUrl, settings.apiKey, model)
            if (!ping.ok) {
                val reason = when (ping.httpCode) {
                    401, 403 -> "El proveedor rechazó la API Key"
                    404 -> "El proveedor no reconoce el modelo «$model»"
                    null -> "No se pudo conectar con el proveedor"
                    else -> "HTTP ${ping.httpCode}"
                }
                return@withContext Result.failure(
                    AiException(listOfNotNull(reason, ping.error?.take(160)).joinToString(" · "))
                )
            }
        }
        Result.success("Conexión correcta · " + models.joinToString(" · "))
    }

    /**
     * Convierte una pregunta de seguimiento ("¿y eso por qué?") en una consulta
     * de búsqueda autónoma usando la conversación, con el modelo rápido. Solo
     * sirve para recuperar mejores fragmentos: el chat sigue recibiendo la
     * pregunta original. Ante cualquier fallo o demora devuelve null.
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
            val who = if (message.role == "user") "Usuario" else "Asistente"
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
                            AiMessage("user", "Conversación:\n$transcript\n\nÚltima pregunta: $question")
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
     * Modelos que expone el proveedor vía GET {base}/models. Sin caché: se
     * consulta cada vez que se abren Ajustes o se pulsa el botón de recarga,
     * de modo que los modelos nuevos del proveedor aparezcan sin actualizar
     * la app. Ante cualquier fallo (URL vacía, red, auth) devuelve una lista
     * vacía, nunca lanza.
     */
    suspend fun listModels(settings: AppSettings): List<String> {
        if (settings.baseUrl.isBlank()) return emptyList()
        return client.listModels(baseUrl = settings.baseUrl, apiKey = settings.apiKey)
    }
}
