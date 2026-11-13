# :core:reader — Native reading engine and PDF viewer

Reading components for Jetpack Compose **without WebView**. It knows nothing about the app database or ViewModels: it receives content and reports what the user does.

## API

| Type | Purpose |
|---|---|
| `NativeReaderController(context, assetDir)` | Renders a chapter (`load(ReaderContent)`), pagination, highlights, references, internal jumps, search (`findAndReveal`), pinch-to-resize text |
| `NativeReaderView(controller, content)` | Composable hosting the paginated `TextView` and gestures |
| `ReaderInteractionListener` | Callbacks: page, selection, highlights, references, taps, end of chapter, font size… |
| `PdfPageReader(...)` | PDF viewer backed by `PdfRenderer`: pager, zoom with sharp re-rendering, night mode that does not invert photos |
| `ReaderTheme`, `ReaderFont`, `HighlightColor`, `readerColors()` | Reader styling (labels are string resources, see `res/values`) |
| `ReaderHighlight`, `ReaderReference`, `ReaderContent` | Input data, independent of persistence |
| `ReaderGestureLayout`, `ReaderKeys` | Gestures (swipe, pinch) and volume keys |

```kotlin
val controller = NativeReaderController(context, File(filesDir, "books/$id/assets"))
controller.listener = myListener
NativeReaderView(controller, ReaderContent(requestId, chapterIndex, html, initialPage = 0, highlights = emptyList()))
```

## Possible improvements

- True column pagination (currently paginated scrolling snapped to lines).
- Configurable hyphenation without a performance cost.
- Text-to-speech and selection/annotation on the original PDF page.
