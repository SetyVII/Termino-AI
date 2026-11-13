package com.termino.core.ai

/**
 * Reasoning effort requested from the provider. [apiValue] is the lowercase
 * value sent as "reasoning_effort" in the OpenAI-compatible API, or null when
 * the setting is OFF and must not be sent.
 *
 * The default is OFF (not MEDIUM) because most models
 * (mistral-small-latest, mistral-large-latest, gpt-4.1...) do not expose that
 * parameter and some providers answer 400 when they receive it. Only
 * reasoning models (Magistral, o1, R1...) accept it.
 */
enum class ReasoningEffort {
    OFF,
    LOW,
    MEDIUM,
    HIGH;

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
 * Directory of OpenAI-compatible endpoints. It only holds the base URL and,
 * optionally, a known key prefix used to *suggest* the provider when an API
 * key is pasted. Models, context size and reasoning support are NOT fixed
 * here: they are queried from the provider (GET /models) and verified with a
 * real request before saving.
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

        /** Provider suggested by the key prefix, or null if ambiguous. */
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

        /** Format heuristic: the user pasted a URL instead of a key. */
        fun looksLikeUrl(input: String): Boolean {
            val t = input.trim().lowercase()
            return t.startsWith("http://") || t.startsWith("https://") ||
                t.startsWith("localhost") || Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?(/.*)?$""").matches(t) ||
                Regex("""^[a-z0-9.-]+\.[a-z]{2,}(:\d+)?/.*$""").matches(t)
        }

        /** Fills in the usual scheme and port for hand-typed URLs. */
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

/** Local network endpoints (Ollama, LM Studio...) that do not require an API key. */
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
