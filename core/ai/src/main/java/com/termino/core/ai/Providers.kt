package com.termino.core.ai

/**
 * Esfuerzo de razonamiento pedido al proveedor. [apiValue] es el valor en
 * minúsculas que se envía como "reasoning_effort" en la API compatible con
 * OpenAI, o null cuando el ajuste está apagado (OFF) y no debe enviarse.
 *
 * El valor por defecto es OFF (no MEDIUM) porque la mayoría de los modelos
 * (mistral-small-latest, mistral-large-latest, gpt-4.1...) no exponen ese
 * parámetro y algunos proveedores responden 400 al recibirlo. Solo los
 * modelos de razonamiento (Magistral, o1, R1...) lo aceptan.
 */
enum class ReasoningEffort(val label: String) {
    OFF("Apagado"),
    LOW("Bajo"),
    MEDIUM("Medio"),
    HIGH("Alto");

    val apiValue: String?
        get() = when (this) {
            OFF -> null
            LOW -> "low"
            MEDIUM -> "medium"
            HIGH -> "high"
        }

    companion object {
        fun fromStorage(value: String?): ReasoningEffort =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OFF
    }
}

/**
 * Directorio de endpoints compatibles con OpenAI. Solo contiene la URL base y,
 * opcionalmente, un prefijo de clave conocido para *sugerir* el proveedor al
 * pegar una API Key. Los modelos, el contexto y el soporte de razonamiento NO
 * se fijan aquí: se consultan al proveedor (GET /models) y se verifican con
 * una petición real antes de guardar.
 */
data class ProviderEndpoint(
    val id: String,
    val label: String,
    val baseUrl: String,
    val keyPrefixes: List<String> = emptyList(),
    val keyRequired: Boolean = true
) {
    companion object {
        const val CUSTOM_ID = "custom"

        val known = listOf(
            ProviderEndpoint("openai", "OpenAI", "https://api.openai.com/v1", listOf("sk-proj-", "sk-svcacct-", "sk-admin-")),
            ProviderEndpoint("anthropic", "Anthropic", "https://api.anthropic.com/v1", listOf("sk-ant-")),
            ProviderEndpoint("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", listOf("AIza")),
            ProviderEndpoint("mistral", "Mistral", "https://api.mistral.ai/v1"),
            ProviderEndpoint("deepseek", "DeepSeek", "https://api.deepseek.com/v1"),
            ProviderEndpoint("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", listOf("sk-or-")),
            ProviderEndpoint("groq", "Groq", "https://api.groq.com/openai/v1", listOf("gsk_")),
            ProviderEndpoint("xai", "xAI", "https://api.x.ai/v1", listOf("xai-")),
            ProviderEndpoint("glm", "GLM", "https://open.bigmodel.cn/api/paas/v4")
        )

        /** Proveedor sugerido por el prefijo de la clave, o null si es ambiguo. */
        fun suggestForKey(key: String): ProviderEndpoint? {
            val trimmed = key.trim()
            return known
                .flatMap { endpoint -> endpoint.keyPrefixes.map { it to endpoint } }
                .sortedByDescending { it.first.length }
                .firstOrNull { trimmed.startsWith(it.first) }
                ?.second
        }

        fun forBaseUrl(baseUrl: String): ProviderEndpoint? {
            val normalized = baseUrl.trim().trimEnd('/').lowercase()
            if (normalized.isBlank()) return null
            return known.firstOrNull { normalized.startsWith(it.baseUrl.lowercase()) }
        }

        /** Heurística de formato: el usuario pegó una URL en lugar de una clave. */
        fun looksLikeUrl(input: String): Boolean {
            val t = input.trim().lowercase()
            return t.startsWith("http://") || t.startsWith("https://") ||
                t.startsWith("localhost") || Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""").matches(t) ||
                Regex("""^[a-z0-9.-]+\.[a-z]{2,}(:\d+)?/.*$""").matches(t)
        }

        /** Completa esquema y puerto típicos para URLs escritas a mano. */
        fun normalizeUrl(input: String): String {
            var t = input.trim().trimEnd('/')
            if (!t.startsWith("http://") && !t.startsWith("https://")) {
                val local = t.startsWith("localhost") || t.firstOrNull()?.isDigit() == true
                t = (if (local) "http://" else "https://") + t
            }
            return t.removeSuffix("/chat/completions").removeSuffix("/models")
        }
    }
}

/** Endpoints de red local (Ollama, LM Studio...) que no exigen API Key. */
fun isLocalEndpoint(baseUrl: String): Boolean {
    val host = runCatching { java.net.URI(baseUrl.trim()).host }.getOrNull()?.lowercase()
        ?: return false
    if (host == "localhost" || host.endsWith(".local") || host == "10.0.2.2") return true
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    return parts[0] == 127 || parts[0] == 10 ||
        (parts[0] == 192 && parts[1] == 168) ||
        (parts[0] == 172 && parts[1] in 16..31)
}
