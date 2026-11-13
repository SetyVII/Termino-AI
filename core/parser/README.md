# :core:parser — EPUB and PDF to a structured book

Turns EPUB and PDF files into a `ParsedBook` (metadata, chapters with clean HTML and plain text, images, references and table of contents). It does not depend on the UI or the RAG module.

## API

```kotlin
val book: ParsedBook = EpubProcessor().parse(inputStream, workDir)
val toc: List<TocItem> = EpubProcessor().extractToc(epubFile)
val pdf: ParsedBook = PdfProcessor(context).parse(inputStream, displayName)
val cover: Bitmap? = PdfCoverRenderer.render(context, pdfFile)
```

Labels written into the parsed book ("Chapter 3", "Page 12", "Figure"…) come from `ParserLabels`. The defaults are English; pass localized values to both processors to match your UI language:

```kotlin
val labels = ParserLabels(chapter = "Capítulo %s", page = "Página %s", /* … */)
val epub = EpubProcessor(labels)
val pdf = PdfProcessor(context, labels)
```

## What it handles

- EPUB 2/3 with `ZipInputStream` + jsoup: HTML cleanup, images (downscaled), **tables rendered to images** (`TableBitmapRenderer`, with `colspan`/`rowspan`), internal links rewritten (`termino://anchor/<chapter>/<id>`), footnotes, bibliography, affiliations and hierarchical TOC (nav.xhtml / toc.ncx).
- PDF with PdfBox-Android: per-page text, de-hyphenation and a notice for scanned pages.

## Possible improvements

- OCR for scanned PDFs.
- MOBI/AZW3 and CBZ support.
- Formula extraction (MathML) as text.
