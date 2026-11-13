package com.termino.core.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible streaming client. Mistral, DeepSeek, OpenAI, GLM, Gemini's
 * OpenAI endpoint and Ollama all use the same SSE payload shape.
 */
class AiStreamingClient(
    private val client: OkHttpClient = defaultClient()
) {
    /**
     * Derived client for short metadata calls (model listing): it inherits
     * the main client's interceptors and configuration but caps every timeout
     * at 15 s, enough for a GET /models without leaving Settings hanging if
     * the provider does not respond.
     */
    private val modelsClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "Termino/1.0 (Android)"
        private const val MAX_SHAPE_RETRIES = 3

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()

        fun endpointFor(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) throw AiException("The provider base URL is empty")
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }

        /**
         * OpenAI-compatible model listing endpoint: GET {base}/models.
         * Accepts the same variety of base URLs as [endpointFor]: if the user
         * pasted the full chat path, it is replaced with /models instead of
         * being appended to.
         */
        fun modelsEndpointFor(baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isBlank()) throw AiException("The provider base URL is empty")
            val base = if (trimmed.endsWith("/chat/completions")) {
                trimmed.removeSuffix("/chat/completions")
            } else {
                trimmed
            }
            return "$base/models"
        }
    }

    fun stream(baseUrl: String, apiKey: String, request: AiRequest): Flow<AiStreamEvent> =
        callbackFlow {
            // Request shape: start from what was learned for this
            // provider+model and adjust to whatever the provider itself
            // rejects (there are no hand-written model lists).
            var shape = learnedShape(baseUrl, request)
            var call = client.newCall(buildHttpRequest(baseUrl, apiKey, request, shape))
            val job = launch(Dispatchers.IO) {
                var fullText = StringBuilder()
                var finished = false
                fun finish() {
                    if (!finished) {
                        finished = true
                        trySend(AiStreamEvent.Completed(fullText.toString()))
                    }
                }
                try {
                    var response = call.execute()
                    // Resilient self-adjustment: if the provider answers 400
                    // for a parameter the model does not accept (reasoning_effort,
                    // max_tokens → max_completion_tokens, fixed temperature),
                    // the request is rebuilt without it and retried without
                    // emitting the intermediate error. What is learned is
                    // remembered for later requests to the same model.
                    var attempts = 0
                    while (response.code == 400 && attempts < MAX_SHAPE_RETRIES) {
                        val errorBody = response.body?.string().orEmpty().take(4000)
                        response.close()
                        val adjusted = adjustShape(shape, errorBody)
                        if (adjusted == null) {
                            val message = parseErrorMessage(errorBody)
                                ?: "The provider responded HTTP 400"
                            trySend(AiStreamEvent.Failed(message))
                            close()
                            return@launch
                        }
                        shape = adjusted
                        rememberShape(baseUrl, request.model, adjusted)
                        attempts++
                        call = client.newCall(buildHttpRequest(baseUrl, apiKey, request, shape))
                        response = call.execute()
                    }
                    response.use { current ->
                        if (!current.isSuccessful) {
                            val errorBody = current.body?.string().orEmpty().take(4000)
                            val message = parseErrorMessage(errorBody)
                                ?: "The provider responded HTTP ${current.code}"
                            trySend(AiStreamEvent.Failed(message))
                            close()
                            return@launch
                        }
                        val source = current.body?.source()
                            ?: throw IOException("The provider response has no body")
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data:")) {
                                val data = line.removePrefix("data:").trim()
                                if (data == "[DONE]") {
                                    finish()
                                    break
                                }
                                if (data.isNotEmpty()) {
                                    val delta = parseDelta(data)
                                    if (delta != null) {
                                        fullText.append(delta)
                                        trySend(AiStreamEvent.Delta(delta))
                                    }
                                }
                            } else if (line.startsWith("{")) {
                                val delta = parseDelta(line.trim())
                                if (delta != null) {
                                    fullText.append(delta)
                                    trySend(AiStreamEvent.Delta(delta))
                                }
                            }
                        }
                        finish()
                        close()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!finished) {
                        trySend(
                            AiStreamEvent.Failed(
                                error.message?.takeIf { it.isNotBlank() }
                                    ?: "Could not connect to the AI provider"
                            )
                        )
                    }
                    close()
                }
            }
            awaitClose {
                call.cancel()
                job.cancel()
            }
        }.buffer(Channel.UNLIMITED)

    suspend fun complete(baseUrl: String, apiKey: String, request: AiRequest): String {
        val builder = StringBuilder()
        var failure: String? = null
        stream(baseUrl, apiKey, request).collect { event ->
            when (event) {
                is AiStreamEvent.Delta -> builder.append(event.text)
                is AiStreamEvent.Completed -> if (builder.isEmpty()) builder.append(event.fullText)
                is AiStreamEvent.Failed -> failure = event.message
            }
        }
        failure?.let { throw AiException(it) }
        if (builder.isEmpty()) throw AiException("The provider returned an empty response")
        return builder.toString()
    }

    /**
     * Model ids offered by the provider (shortcut for [fetchModels]). Never throws.
     */
    suspend fun listModels(baseUrl: String, apiKey: String): List<String> =
        fetchModels(baseUrl, apiKey).models.map { it.id }

    /**
     * GET {base}/models with whatever metadata the provider exposes (context,
     * reasoning support, whether it is a chat model). Also returns the HTTP
     * code to tell an invalid key (401/403) from an endpoint without a
     * listing (404) or a network failure (code == null). Never throws.
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): ModelsResponse =
        withContext(Dispatchers.IO) {
            val trimmedBase = baseUrl.trim()
            if (trimmedBase.isBlank()) return@withContext ModelsResponse(null, emptyList(), "Empty URL")
            val request = runCatching {
                Request.Builder()
                    .url(modelsEndpointFor(trimmedBase))
                    .get()
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .applyAuth(trimmedBase, apiKey)
                    .build()
            }.getOrElse { return@withContext ModelsResponse(null, emptyList(), it.message) }
            try {
                modelsClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        ModelsResponse(response.code, emptyList(), parseErrorMessage(body))
                    } else {
                        ModelsResponse(response.code, parseModels(body), null)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ModelsResponse(null, emptyList(), error.message)
            }
        }

    /**
     * Minimal non-streaming request to verify that the key and the model
     * really work. Applies the same parameter self-adjustment as streaming
     * and returns the final HTTP code.
     */
    suspend fun ping(baseUrl: String, apiKey: String, model: String): PingResult =
        withContext(Dispatchers.IO) {
            val request = AiRequest(
                model = model,
                messages = listOf(AiMessage("user", "Reply with OK.")),
                temperature = 0f,
                maxTokens = 16,
                reasoningEffort = com.termino.core.ai.ReasoningEffort.OFF
            )
            var shape = learnedShape(baseUrl, request)
            repeat(MAX_SHAPE_RETRIES + 1) {
                val http = runCatching {
                    buildHttpRequest(baseUrl, apiKey, request, shape, stream = false)
                }.getOrElse { return@withContext PingResult(null, it.message) }
                try {
                    modelsClient.newBuilder().callTimeout(40, TimeUnit.SECONDS)
                        .readTimeout(40, TimeUnit.SECONDS).build()
                        .newCall(http).execute().use { response ->
                            val body = response.body?.string().orEmpty().take(4000)
                            if (response.isSuccessful) return@withContext PingResult(response.code, null)
                            val adjusted = if (response.code == 400) adjustShape(shape, body) else null
                            if (adjusted == null) {
                                return@withContext PingResult(response.code, parseErrorMessage(body))
                            }
                            shape = adjusted
                            rememberShape(baseUrl, model, adjusted)
                        }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return@withContext PingResult(null, error.message)
                }
            }
            PingResult(400, "The provider rejected the request")
        }

    private fun parseModels(body: String): List<ProviderModel> {
        if (body.isBlank()) return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull()
        val data = json?.optJSONArray("data")
            ?: json?.optJSONArray("models")
            ?: runCatching { JSONArray(body) }.getOrNull()
            ?: return emptyList()
        val models = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val rawId = item.optString("id", "").ifBlank { item.optString("name", "") }
                val id = rawId.removePrefix("models/").trim()
                if (id.isBlank()) continue
                add(ProviderModel(id = id, contextLength = contextLength(item),
                    supportsReasoning = reasoningSupport(item), isChat = chatSupport(item)))
            }
        }
        return models.distinctBy { it.id }.sortedBy { it.id }
    }

    private fun contextLength(item: JSONObject): Int? {
        val keys = listOf("context_length", "max_context_length", "context_window",
            "max_model_len", "input_token_limit", "max_input_tokens")
        keys.forEach { key -> item.optInt(key, 0).takeIf { it > 0 }?.let { return it } }
        return item.optJSONObject("top_provider")?.optInt("context_length", 0)?.takeIf { it > 0 }
    }

    private fun reasoningSupport(item: JSONObject): Boolean? {
        item.optJSONArray("supported_parameters")?.let { params ->
            val names = (0 until params.length()).map { params.optString(it) }
            return names.any { it == "reasoning" || it == "reasoning_effort" || it == "include_reasoning" }
        }
        val caps = item.optJSONObject("capabilities") ?: return null
        listOf("reasoning", "thinking").forEach { key ->
            if (caps.has(key)) return caps.optBoolean(key)
        }
        return null
    }

    private fun chatSupport(item: JSONObject): Boolean? {
        item.optJSONObject("capabilities")?.let { caps ->
            if (caps.has("completion_chat")) return caps.optBoolean("completion_chat")
            if (caps.has("chat")) return caps.optBoolean("chat")
        }
        item.optJSONObject("architecture")?.optJSONArray("output_modalities")?.let { out ->
            val values = (0 until out.length()).map { out.optString(it) }
            return values.contains("text")
        }
        return when (item.optString("type", "").lowercase()) {
            "chat", "language", "llm", "vlm" -> true
            "embedding", "embeddings", "image", "audio", "moderation", "rerank",
            "transcription", "tts", "stt" -> false
            else -> null
        }
    }

    private fun Request.Builder.applyAuth(baseUrl: String, apiKey: String): Request.Builder {
        val key = apiKey.trim()
        if (key.isBlank()) return this
        header("Authorization", "Bearer $key")
        // Anthropic accepts Bearer on its OpenAI-compatible layer, but its
        // model listing uses x-api-key + version.
        if (baseUrl.contains("anthropic.com")) {
            header("x-api-key", key)
            header("anthropic-version", "2023-06-01")
        }
        return this
    }

    private fun buildHttpRequest(
        baseUrl: String,
        apiKey: String,
        request: AiRequest,
        shape: RequestShape,
        stream: Boolean = true
    ): Request = Request.Builder()
        .url(endpointFor(baseUrl))
        .post(buildRequestBody(request, shape, stream).toRequestBody(JSON_MEDIA_TYPE))
        .header("Content-Type", "application/json")
        .header("Accept", if (stream) "text/event-stream" else "application/json")
        .header("User-Agent", USER_AGENT)
        .applyAuth(baseUrl, apiKey)
        .build()

    /**
     * Request JSON body according to the learned [RequestShape]: allows
     * omitting "reasoning_effort" or "temperature" and using
     * "max_completion_tokens" when the provider requires it.
     */
    private fun buildRequestBody(request: AiRequest, shape: RequestShape, stream: Boolean): String {
        val messages = JSONArray()
        request.messages.forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", message.content)
            )
        }
        val body = JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("stream", stream)
        if (shape.includeTemperature) body.put("temperature", request.temperature.toDouble())
        body.put(if (shape.useMaxCompletionTokens) "max_completion_tokens" else "max_tokens", request.maxTokens)
        if (shape.includeReasoning) {
            request.reasoningEffort.apiValue?.let { effort -> body.put("reasoning_effort", effort) }
        }
        return body.toString()
    }

    private val learnedShapes = java.util.concurrent.ConcurrentHashMap<String, RequestShape>()

    private fun shapeKey(baseUrl: String, model: String) =
        baseUrl.trim().trimEnd('/').lowercase() + "|" + model.trim()

    private fun learnedShape(baseUrl: String, request: AiRequest): RequestShape {
        val learned = learnedShapes[shapeKey(baseUrl, request.model)] ?: RequestShape()
        return learned.copy(
            includeReasoning = learned.includeReasoning && request.reasoningEffort.apiValue != null
        )
    }

    private fun rememberShape(baseUrl: String, model: String, shape: RequestShape) {
        val key = shapeKey(baseUrl, model)
        val previous = learnedShapes[key] ?: RequestShape()
        // Restrictions only accumulate; an attempt without reasoning must
        // not "forget" that the model does support it.
        learnedShapes[key] = previous.copy(
            includeReasoning = previous.includeReasoning &&
                !(shape.reasoningRejected),
            useMaxCompletionTokens = previous.useMaxCompletionTokens || shape.useMaxCompletionTokens,
            includeTemperature = previous.includeTemperature && shape.includeTemperature
        )
    }

    /** Adjusts the shape based on the provider's 400, or null if it is not a parameter problem. */
    private fun adjustShape(shape: RequestShape, errorBody: String): RequestShape? {
        val body = errorBody.lowercase()
        if (shape.includeReasoning && isReasoningEffortRejection(errorBody)) {
            return shape.copy(includeReasoning = false, reasoningRejected = true)
        }
        if (!shape.useMaxCompletionTokens && body.contains("max_tokens") &&
            (body.contains("max_completion_tokens") || body.contains("unsupported"))
        ) {
            return shape.copy(useMaxCompletionTokens = true)
        }
        if (shape.includeTemperature && body.contains("temperature") &&
            (body.contains("unsupported") || body.contains("not support") ||
                body.contains("only the default") || body.contains("does not support"))
        ) {
            return shape.copy(includeTemperature = false)
        }
        return null
    }

    /**
     * Detects the specific "reasoning_effort" 400: the provider mentions the
     * field (e.g. "reasoning_effort is not enabled for this model") or combines
     * "reasoning" with "not support"/"not enabled"/"unsupported". Any other
     * 400 must be shown as is: it does not trigger the retry.
     */
    private fun isReasoningEffortRejection(errorBody: String): Boolean {
        val body = errorBody.lowercase()
        if (body.contains("reasoning_effort")) return true
        val mentionsReasoning = body.contains("reasoning")
        val mentionsRejection = body.contains("not support") ||
            body.contains("not enabled") ||
            body.contains("unsupported")
        return mentionsReasoning && mentionsRejection
    }

    private fun parseDelta(data: String): String? {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
        if (json.has("error")) {
            val error = json.opt("error")
            return null
        }
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta")
        val message = choice.optJSONObject("message")
        val content = when {
            delta != null && delta.has("content") -> delta.optString("content", "")
            message != null && message.has("content") -> message.optString("content", "")
            choice.has("text") -> choice.optString("text", "")
            else -> ""
        }
        return content.takeIf { it.isNotEmpty() }
    }

    private fun parseErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            val error = json.opt("error")
            if (error is JSONObject) {
                val message = error.optString("message", "")
                if (message.isNotBlank()) return message.take(900)
            }
            val message = json.optString("message", "")
            if (message.isNotBlank()) return message.take(900)
        }
        return body.take(900)
    }
}
