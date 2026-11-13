# :core:reader — Motor de lectura nativo y visor PDF

Componentes de lectura **sin WebView** para Jetpack Compose. No conoce la base de datos ni los ViewModels de la app: recibe contenido y avisa de lo que hace el usuario.

## API

| Tipo | Para qué |
|---|---|
| `NativeReaderController(context, assetDir)` | Pinta un capítulo (`load(ReaderContent)`), paginación, subrayados, referencias, saltos internos, búsqueda (`findAndReveal`), pellizco de tamaño de letra |
| `NativeReaderView(controller, content)` | Composable que monta el `TextView` paginado y los gestos |
| `ReaderInteractionListener` | Callbacks: página, selección, subrayados, referencias, toques, fin de capítulo, tamaño de letra… |
| `PdfPageReader(...)` | Visor PDF con `PdfRenderer`: paginador, zoom con re-render nítido, modo noche que no invierte fotos |
| `ReaderTheme`, `ReaderFont`, `HighlightColor`, `readerColors()` | Estilo del lector |
| `ReaderHighlight`, `ReaderReference`, `ReaderContent` | Datos de entrada, independientes de la persistencia |
| `ReaderGestureLayout`, `ReaderKeys` | Gestos (deslizar, pellizcar) y teclas de volumen |

```kotlin
val controller = NativeReaderController(context, File(filesDir, "books/$id/assets"))
controller.listener = miListener
NativeReaderView(controller, ReaderContent(requestId, chapterIndex, html, initialPage = 0, highlights = emptyList()))
```

## Por dónde mejorar

- Paginación real por columnas (hoy es scroll paginado con ajuste a líneas).
- Guiones (hyphenation) configurables sin coste de rendimiento.
- Texto a voz y selección/anotación sobre el PDF original.
