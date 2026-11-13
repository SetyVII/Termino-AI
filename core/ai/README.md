# :core:ai — OpenAI-compatible AI client

Talks to any OpenAI-style API (OpenAI, Anthropic, Gemini, Mistral, DeepSeek, OpenRouter, Groq, xAI, GLM, Ollama, LM Studio, vLLM…) **without hand-written model catalogs**. It does not depend on the app or any other module.

## API

| Type | Purpose |
|---|---|
| `AiStreamingClient` | `stream()` (SSE as `Flow<AiStreamEvent>`), `complete()`, `fetchModels()` with metadata, `ping()` |
| `ProviderVerifier` | `verify(input, endpoint)`: resolves the URL, lists models, picks them and **tests** them with a real request |
| `ProviderEndpoint` | Directory of base URLs and key prefixes (only used to *suggest* a provider) |
| `ReasoningEffort`, `AiRequest`, `AiMessage`, `ProviderModel` | Data models |
| `isLocalEndpoint(url)` | Detects local network servers (keys optional) |

```kotlin
val client = AiStreamingClient()
client.stream(baseUrl, apiKey, AiRequest(model, listOf(AiMessage("user", "Hello")))).collect { event -> … }

when (val r = ProviderVerifier(client).verify(input = apiKey, endpoint = ProviderEndpoint.known.first())) {
    is VerifyResult.Success -> r.connection.chatModel
    is VerifyResult.Failure -> r.reason
}
```

## Design decisions

- **Learned request shape** (`RequestShape`): if the provider answers 400 because of `reasoning_effort`, `max_tokens` or `temperature`, the request is retried without that parameter (or with `max_completion_tokens`) and the result is remembered per model.
- The key is only sent to the chosen provider; it is never tried against several.

## Possible improvements

- Native Anthropic/Gemini APIs (currently through their OpenAI-compatible layer).
- Tools (*function calling*) and structured output.
- Persisting the learned `RequestShape` across sessions.
