# :core:parser — EPUB y PDF a libro estructurado

Convierte archivos EPUB y PDF en un `ParsedBook` (metadatos, capítulos con HTML limpio y texto plano, imágenes, referencias e índice). No depende de la UI ni del RAG.

## API

```kotlin
val book: ParsedBook = EpubProcessor().parse(inputStream, workDir)
val toc: List<TocItem> = EpubProcessor().extractToc(epubFile)
val pdf: ParsedBook = PdfProcessor(context).parse(inputStream, displayName)
val cover: Bitmap? = PdfCoverRenderer.render(context, pdfFile) // portada
```

## Qué resuelve

- EPUB 2/3 con `ZipInputStream` + jsoup: limpieza de HTML, imágenes (reescaladas), **tablas a imagen** (`TableBitmapRenderer`, con `colspan`/`rowspan`), enlaces internos reescritos (`termino://anchor/<capítulo>/<id>`), notas al pie, bibliografía, afiliaciones y TOC jerárquico (nav.xhtml / toc.ncx).
- PDF con PdfBox-Android: texto por página, desguionado y aviso de páginas escaneadas.

## Por dónde mejorar

- OCR para PDFs escaneados.
- Soporte de MOBI/AZW3 y CBZ.
- Extracción de fórmulas (MathML) como texto.
