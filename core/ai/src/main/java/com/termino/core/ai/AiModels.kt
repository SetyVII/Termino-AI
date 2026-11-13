package com.termino.core.ai


data class AiMessage(
    val role: String,
    val content: String
)

data class AiRequest(
    val model: String,
    val messages: List<AiMessage>,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 1400,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
)

sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data class Completed(val fullText: String) : AiStreamEvent
    data class Failed(val message: String) : AiStreamEvent
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Request shape accepted by a specific provider+model. It is learned at
 * runtime from the provider's own HTTP 400 responses instead of keeping
 * hand-written model lists.
 */
data class RequestShape(
    val includeReasoning: Boolean = true,
    val useMaxCompletionTokens: Boolean = false,
    val includeTemperature: Boolean = true,
    val reasoningRejected: Boolean = false
)

/** A model as described by GET /models; metadata fields are optional. */
data class ProviderModel(
    val id: String,
    val contextLength: Int? = null,
    val supportsReasoning: Boolean? = null,
    val isChat: Boolean? = null
)

data class ModelsResponse(
    val httpCode: Int?,
    val models: List<ProviderModel>,
    val error: String?
) {
    val isAuthError: Boolean get() = httpCode == 401 || httpCode == 403
}

data class PingResult(val httpCode: Int?, val error: String?) {
    val ok: Boolean get() = httpCode != null && httpCode in 200..299
}
