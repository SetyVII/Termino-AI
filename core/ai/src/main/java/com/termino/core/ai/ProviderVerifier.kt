package com.termino.core.ai

import java.net.URI

/** Visible verification phases, used to animate progress in the UI. */
enum class VerifyStage { CONNECTING, LISTING_MODELS, TESTING_MODEL, DONE }

enum class VerifyFailure {
    MISSING_INPUT,
    MISSING_PROVIDER,
    BAD_KEY,
    UNREACHABLE,
    NO_MODELS,
    MODEL_REJECTED
}

data class VerifiedConnection(
    val baseUrl: String,
    val apiKey: String,
    val providerLabel: String,
    val models: List<ProviderModel>,
    val fastModel: String,
    val chatModel: String
)

sealed interface VerifyResult {
    data class Success(val connection: VerifiedConnection) : VerifyResult
    data class Failure(val reason: VerifyFailure, val detail: String? = null) : VerifyResult
}

/**
 * Verifies a connection against the real provider, with no fixed catalogs:
 *  1. Resolves the base URL (typed by hand, or the chosen provider's).
 *  2. GET /models with the key: 401/403 = invalid key.
 *  3. Picks the fast and chat models from what the provider returns
 *     (or keeps the ones the user set under Advanced).
 *  4. Sends a minimal real request with each model; if one fails, tries the
 *     next candidate.
 *
 * The key is only sent to the chosen provider: it is never "tried" against
 * several providers at once, so it does not leak to third parties.
 */
class ProviderVerifier(private val client: AiStreamingClient) {

    suspend fun verify(
        input: String,
        endpoint: ProviderEndpoint?,
        apiKeyForUrl: String = "",
        manualFastModel: String = "",
        manualChatModel: String = "",
        onStage: (VerifyStage) -> Unit = {}
    ): VerifyResult {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return VerifyResult.Failure(VerifyFailure.MISSING_INPUT)

        val isUrl = ProviderEndpoint.looksLikeUrl(trimmed)
        val apiKey: String
        val candidates: List<String>
        val label: String
        if (isUrl) {
            val base = ProviderEndpoint.normalizeUrl(trimmed)
            apiKey = apiKeyForUrl.trim()
            candidates = buildList {
                add(base)
                if (!base.substringAfter("://").contains('/')) add("$base/v1")
            }
            label = ProviderEndpoint.forBaseUrl(base)?.label ?: hostLabel(base)
        } else {
            val chosen = endpoint ?: return VerifyResult.Failure(VerifyFailure.MISSING_PROVIDER)
            apiKey = trimmed
            candidates = listOf(chosen.baseUrl)
            label = chosen.label
        }

        onStage(VerifyStage.CONNECTING)
        var resolvedBase: String? = null
        var models: List<ProviderModel> = emptyList()
        var lastError: String? = null
        var reachedServer = false
        for (base in candidates) {
            val response = client.fetchModels(base, apiKey)
            if (response.isAuthError) {
                return VerifyResult.Failure(VerifyFailure.BAD_KEY, response.error)
            }
            if (response.httpCode != null) reachedServer = true
            if (response.httpCode?.let { it in 200..299 } == true) {
                resolvedBase = base
                models = response.models
                break
            }
            lastError = response.error
        }

        onStage(VerifyStage.LISTING_MODELS)
        val manualChat = manualChatModel.trim()
        val manualFast = manualFastModel.trim()
        if (resolvedBase == null) {
            // No model list: we can only continue if the user set a model
            // and the server at least responded.
            if (!reachedServer) return VerifyResult.Failure(VerifyFailure.UNREACHABLE, lastError)
            if (manualChat.isBlank()) return VerifyResult.Failure(VerifyFailure.NO_MODELS, lastError)
            resolvedBase = candidates.first()
        }

        val chatCandidates = if (manualChat.isNotBlank()) listOf(manualChat) else rankChat(models)
        if (chatCandidates.isEmpty()) return VerifyResult.Failure(VerifyFailure.NO_MODELS)

        onStage(VerifyStage.TESTING_MODEL)
        var chatModel: String? = null
        var pingError: String? = null
        for (candidate in chatCandidates.take(MAX_PING_CANDIDATES)) {
            val ping = client.ping(resolvedBase, apiKey, candidate)
            if (ping.ok) {
                chatModel = candidate
                break
            }
            if (ping.httpCode == 401 || ping.httpCode == 403) {
                return VerifyResult.Failure(VerifyFailure.BAD_KEY, ping.error)
            }
            if (ping.httpCode == null && !isLocalEndpoint(resolvedBase)) {
                return VerifyResult.Failure(VerifyFailure.UNREACHABLE, ping.error)
            }
            pingError = ping.error
        }
        if (chatModel == null) return VerifyResult.Failure(VerifyFailure.MODEL_REJECTED, pingError)

        val fastCandidates = if (manualFast.isNotBlank()) listOf(manualFast) else rankFast(models)
        var fastModel: String = chatModel
        for (candidate in fastCandidates.filter { it != chatModel }.take(MAX_PING_CANDIDATES - 1)) {
            if (client.ping(resolvedBase, apiKey, candidate).ok) {
                fastModel = candidate
                break
            }
        }

        onStage(VerifyStage.DONE)
        return VerifyResult.Success(
            VerifiedConnection(
                baseUrl = resolvedBase,
                apiKey = apiKey,
                providerLabel = label,
                models = models,
                fastModel = fastModel,
                chatModel = chatModel
            )
        )
    }

    private fun hostLabel(base: String): String =
        runCatching { URI(base).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: base

    companion object {
        private const val MAX_PING_CANDIDATES = 3

        private val nonChatMarkers = listOf(
            "embed", "whisper", "tts", "dall-e", "moderation", "ocr", "audio", "transcri",
            "image", "realtime", "rerank", "guard", "speech", "veo", "imagen", "search-preview"
        )
        private val lightMarkers = listOf(
            "mini", "small", "flash", "lite", "haiku", "nano", "tiny", "instant", "fast",
            ":1b", ":3b", ":4b", ":7b", ":8b", "-8b", "-7b"
        )
        private val strongMarkers = listOf(
            "large", "pro", "plus", "max", "opus", "sonnet", "medium", "chat"
        )
        private val datedSuffix = Regex("""\d{4}-?\d{2}-?\d{2}|-\d{4}$""")

        /** Models the provider flags or names as chat-capable. */
        fun chatCapable(models: List<ProviderModel>): List<ProviderModel> =
            models.filter { model ->
                model.isChat != false && nonChatMarkers.none { model.id.lowercase().contains(it) }
            }

        private fun stability(id: String): Int {
            val name = id.lowercase()
            var score = 0
            if (name.contains("latest")) score += 2
            if (name.contains("preview") || name.contains("exp") || name.contains("beta")) score -= 3
            if (datedSuffix.containsMatchIn(name)) score -= 1
            return score
        }

        fun rankChat(models: List<ProviderModel>): List<String> =
            chatCapable(models).sortedWith(
                compareByDescending<ProviderModel> { model ->
                    val name = model.id.lowercase()
                    var score = stability(name)
                    if (strongMarkers.any { name.contains(it) }) score += 3
                    if (lightMarkers.any { name.contains(it) }) score -= 2
                    model.contextLength?.let { if (it >= 64_000) score += 1 }
                    score
                }.thenBy { it.id.length }
            ).map { it.id }

        fun rankFast(models: List<ProviderModel>): List<String> =
            chatCapable(models).sortedWith(
                compareByDescending<ProviderModel> { model ->
                    val name = model.id.lowercase()
                    var score = stability(name)
                    if (lightMarkers.any { name.contains(it) }) score += 3
                    if (model.supportsReasoning == true) score -= 1
                    score
                }.thenBy { it.id.length }
            ).map { it.id }
    }
}
