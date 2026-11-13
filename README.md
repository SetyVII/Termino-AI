# Término

Lector nativo de EPUB y PDF para Android (móvil y tablet) con un asistente de IA que conoce el libro que lees.

- Todo vive en el dispositivo: libros, índice, notas y chats. Sin cuentas ni telemetría.
- La IA la pones tú: tu API Key o tu propio servidor (cualquier API estilo OpenAI, Ollama, LM Studio…).
- Búsqueda local en el libro (BM25 + vectores), citas de capítulo y página, subrayados y marcadores.

## Instalar

Requisitos: JDK 17 y Android SDK 36 (`ANDROID_HOME` o `sdk.dir` en `local.properties`).

```bash
./gradlew :app:assembleDebug
```

El APK queda en `app/build/outputs/apk/debug/`. Instálalo con `adb install -r` o cópialo al dispositivo.

## Primeros pasos

1. Abre la app y añade un EPUB o PDF.
2. Para usar la IA, pega tu API Key o la URL de tu servidor en la bienvenida o en Ajustes.

## Modificarlo

Módulos independientes, cada uno con su README: [`core/ai`](core/ai), [`core/rag`](core/rag), [`core/parser`](core/parser), [`core/reader`](core/reader). La app está en `app/`. Pruebas: `./gradlew :app:testDebugUnitTest`.

## Licencia

GPL-3.0, ver [`LICENSE`](LICENSE). Basado en [ReadAny](https://github.com/codedogQBY/ReadAny) de codedogQBY. Atribuciones en [`NOTICE.md`](NOTICE.md).
