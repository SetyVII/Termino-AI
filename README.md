# Término

Native EPUB and PDF reader for Android (phone and tablet) with an AI assistant that knows the book you are reading.

- Your library stays on the device: books, search index, notes and chats. No accounts, no telemetry.
- Bring your own AI: your API key or your own server (any OpenAI-compatible API, Ollama, LM Studio…). Questions and the relevant passages are sent only to the provider you configure.
- Local in-book search (BM25 + vectors), chapter and page citations, highlights and bookmarks.
- UI available in English and Spanish.

## Build

Requirements: JDK 17 and Android SDK 36 (`ANDROID_HOME` or `sdk.dir` in `local.properties`).

```bash
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Install it with `adb install -r` or copy it to the device.

## Getting started

1. Open the app and add an EPUB or PDF (or try one of the sample books).
2. To use the assistant, paste your API key or your server URL during onboarding or in Settings.

## Project layout

Independent modules, each with its own README: [`core/ai`](core/ai), [`core/rag`](core/rag), [`core/parser`](core/parser), [`core/reader`](core/reader). The app lives in `app/`.

Run the unit tests with:

```bash
./gradlew testDebugUnitTest
```

## Translations

Strings live in `app/src/main/res/values/strings.xml` (English, default) and `core/reader/src/main/res/values/strings.xml`. Add a `values-<lang>` folder with a translated `strings.xml`; Android 13+ per-app language settings pick it up automatically.

## License

GPL-3.0, see [`LICENSE`](LICENSE). Based on [ReadAny](https://github.com/codedogQBY/ReadAny) by codedogQBY. Attributions in [`NOTICE.md`](NOTICE.md).
