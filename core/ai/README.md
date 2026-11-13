# :core:ai — Cliente de IA compatible con OpenAI

Habla con cualquier API estilo OpenAI (OpenAI, Anthropic, Gemini, Mistral, DeepSeek, OpenRouter, Groq, xAI, GLM, Ollama, LM Studio, vLLM…) **sin catálogos de modelos escritos a mano**. No depende de la app ni de ningún otro módulo.

## API

| Tipo | Para qué |
|---|---|
| `AiStreamingClient` | `stream()` (SSE como `Flow<AiStreamEvent>`), `complete()`, `fetchModels()` con metadatos, `ping()` |
| `ProviderVerifier` | `verify(input, endpoint)`: resuelve la URL, lista modelos, elige y **prueba** con una petición real |
| `ProviderEndpoint` | Directorio de URLs base y prefijos de clave (solo para *sugerir* proveedor) |
| `ReasoningEffort`, `AiRequest`, `AiMessage`, `ProviderModel` | Modelos de datos |
| `isLocalEndpoint(url)` | Detecta servidores de red local (claves opcionales) |

```kotlin
val client = AiStreamingClient()
client.stream(baseUrl, apiKey, AiRequest(model, listOf(AiMessage("user", "Hola")))).collect { event -> … }

when (val r = ProviderVerifier(client).verify(input = apiKey, endpoint = ProviderEndpoint.known.first())) {
    is VerifyResult.Success -> r.connection.chatModel
    is VerifyResult.Failure -> r.reason
}
```

## Decisiones

- **Forma de petición aprendida** (`RequestShape`): si el proveedor responde 400 por `reasoning_effort`, `max_tokens` o `temperature`, se reintenta sin ese parámetro (o con `max_completion_tokens`) y se recuerda por modelo.
- La clave solo se envía al proveedor elegido; nunca se prueba contra varios.

## Por dónde mejorar

- Soporte nativo de la API de Anthropic/Gemini (hoy vía su capa compatible con OpenAI).
- Herramientas (*function calling*) y respuestas estructuradas.
- Persistir `RequestShape` aprendido entre sesiones.
