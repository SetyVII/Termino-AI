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
 * Forma de la petición que acepta un proveedor+modelo concreto. Se aprende
 * en tiempo de ejecución a partir de los 400 del propio proveedor en lugar
 * de mantener listas de modelos a mano.
 */
data class RequestShape(
    val includeReasoning: Boolean = true,
    val useMaxCompletionTokens: Boolean = false,
    val includeTemperature: Boolean = true,
    val reasoningRejected: Boolean = false
)

/** Modelo tal como lo describe GET /models; los metadatos son opcionales. */
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
