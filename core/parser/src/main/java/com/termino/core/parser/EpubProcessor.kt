package com.termino.core.parser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.jsoup.safety.Safelist

class EpubProcessor {

    companion object {
        /**
         * Number that opens a chapter title, e.g. "1. Introduction",
         * "2.1 Background". The required trailing separator/whitespace keeps
         * plain years ("1984") and ordinals ("3rd") from matching.
         */
        private val leadingChapterNumber = Regex("^\\s*(\\d{1,3}(?:\\.\\d{1,3}){0,3})\\.?[ \\t\\n]+")

        /** Explicit word form, e.g. "Capítulo 4", "Chapter 12", "Cap. 3". */
        private val chapterWordAndNumber = Regex(
            "^\\s*(?:cap[íi]tulo|cap|chapter|chap)\\.?[ \\t]+(\\d{1,3})\\b",
            RegexOption.IGNORE_CASE
        )

        /** Raster images wider than this are downsampled on import. */
        private const val MAX_ASSET_WIDTH = 1600
        private const val MAX_ASSET_HEIGHT = 4096
        private const val JPEG_QUALITY = 85
        private const val MAX_SAMPLE_SIZE = 64

        /** A large PNG is treated as a photo (JPEG q85); small ones stay PNG. */
        private const val PHOTO_PNG_BYTES = 512L * 1024L

        private val rasterExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp")
        private val photoExtensions = setOf("jpg", "jpeg", "webp", "bmp")

        // Ids semánticos del EPUB. Se anclan a principio/fin para no capturar
        // variantes como "Sec5Heading" o "AffiliationText".
        private val AFFILIATION_ID = Regex("^Aff\\d+$", RegexOption.IGNORE_CASE)
        private val CONTACT_ID = Regex("^ContactOfAuthor\\d+$", RegexOption.IGNORE_CASE)
        private val SECTION_ID = Regex("^Sec\\d+$", RegexOption.IGNORE_CASE)
        private val FIGURE_ID = Regex("^Fig\\d+$", RegexOption.IGNORE_CASE)
        private val TABLE_ID = Regex("^Tab\\d+$", RegexOption.IGNORE_CASE)
        private val BIBLIOGRAPHY_ID = Regex("^Bib\\d+$", RegexOption.IGNORE_CASE)
    }


    private data class ManifestItem(
        val id: String,
        val href: String,
        val mediaType: String,
        val properties: String,
        val file: File
    )

    private data class ChapterSource(
        val index: Int,
        val file: File,
        val html: String
    )

    private data class TableReplacement(
        val token: String,
        val asset: ParsedAsset,
        val text: String,
        val caption: String
    )

    suspend fun parse(input: InputStream, workDir: File): ParsedBook = withContext(Dispatchers.IO) {
        val extractDir = File(workDir, "epub_extract")
        extractDir.mkdirs()
        extractZip(input, extractDir)

        val containerFile = File(extractDir, "META-INF/container.xml")
        if (!containerFile.exists()) {
            throw IllegalArgumentException("El EPUB no contiene META-INF/container.xml")
        }
        if (File(extractDir, "META-INF/encryption.xml").exists()) {
            throw IllegalArgumentException("Este EPUB parece estar protegido con DRM y no se puede abrir")
        }

        val container = Jsoup.parse(containerFile, "UTF-8", "", Parser.xmlParser())
        val opfPath = container.select("rootfile").firstOrNull()?.attr("full-path")
            ?: throw IllegalArgumentException("El EPUB no declara un archivo OPF")
        val opfFile = safeFile(extractDir, opfPath)
            ?: throw IllegalArgumentException("Ruta OPF no válida")
        if (!opfFile.exists()) throw IllegalArgumentException("No se encontró el OPF del EPUB")

        val opf = Jsoup.parse(opfFile, "UTF-8", "", Parser.xmlParser())
        val opfDir = opfFile.parentFile ?: extractDir
        val title = firstText(opf, "title") ?: opfFile.nameWithoutExtension
        val author = firstText(opf, "creator") ?: "Autor desconocido"
        val language = firstText(opf, "language")
        val description = firstText(opf, "description")

        val manifest = LinkedHashMap<String, ManifestItem>()
        opf.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            if (id.isNotBlank() && href.isNotBlank()) {
                val file = resolveRelative(opfDir, href, extractDir)
                if (file != null && file.exists() && file.isFile) {
                    manifest[id] = ManifestItem(
                        id = id,
                        href = href,
                        mediaType = item.attr("media-type"),
                        properties = item.attr("properties"),
                        file = file
                    )
                }
            }
        }

        val tocMap = buildTocMap(opf, opfDir, manifest, extractDir)
        val spineIds = opf.select("spine > itemref").mapNotNull { it.attr("idref").takeIf(String::isNotBlank) }
        if (spineIds.isEmpty()) throw IllegalArgumentException("El EPUB no tiene capítulos en el spine")

        val assetMap = LinkedHashMap<String, ParsedAsset>()
        val chapters = mutableListOf<ParsedChapter>()
        val chapterSources = mutableListOf<ChapterSource>()
        val references = mutableListOf<ParsedReference>()
        var chapterIndex = 0

        spineIds.forEach { id ->
            val item = manifest[id] ?: return@forEach
            val isHtml = item.mediaType.contains("html", ignoreCase = true) ||
                item.file.extension.lowercase() in setOf("xhtml", "html", "htm")
            if (!isHtml) return@forEach

            val document = runCatching { Jsoup.parse(item.file, "UTF-8", "") }.getOrNull()
                ?: return@forEach
            val body = document.body() ?: return@forEach

            // Referencias semánticas del EPUB (afiliaciones, contacto,
            // secciones, figuras, tablas y bibliografía). Se extraen antes
            // del barrido heurístico de notas/citas para que sus ids no se
            // confundan con notas al pie.
            extractSemanticReferences(body, chapterIndex, references)

            body.select("[id]").forEach { element ->
                val id = element.id().trim()
                if (id.isBlank()) return@forEach
                val tag = element.tagName().lowercase()
                if (tag !in setOf("li", "aside", "p", "div", "span")) return@forEach
                // Descarta contenedores reales de notas (una sección de
                // notas al pie completa, una bibliografía, una lista de
                // referencias), pero NO notas individuales que contengan
                // párrafos con id propio: un <div class="FootnoteContent"
                // id="Fn1"> con un <p id="Par5"> dentro es una nota válida.
                // La comprobación anterior (element.select("[id]").size > 1)
                // descartaba exactamente esas notas y dejaba book_references
                // sin ninguna clave Fn*.
                val containerMarker = (element.className() + " " + id).lowercase()
                if (containerMarker.contains("section") ||
                    containerMarker.contains("notes") ||
                    containerMarker.contains("bibliography") ||
                    containerMarker.contains("references")
                ) {
                    return@forEach
                }
                val marker = (
                    element.attr("epub:type") + " " +
                        element.attr("role") + " " +
                        element.className() + " " +
                        id
                    ).lowercase()
                val isNoteMarker = marker.contains("note") ||
                    marker.contains("noteref") ||
                    marker.contains("reference") ||
                    marker.contains("bibliograph") ||
                    marker.contains("citation") ||
                    marker.contains("endnote") ||
                    marker.contains("footnote") ||
                    tag == "aside"
                if (!isNoteMarker) {
                    return@forEach
                }
                val cleanElement = element.clone().apply {
                    select("a.footnote-back, a.reversefootnote, a[href*='ref'], a[href*='Ref']").forEach { link ->
                        val linkText = link.text().trim()
                        if (linkText in setOf("↩", "↩︎", "^", "↑", "back", "return") || linkText.isBlank()) {
                            link.remove()
                        }
                    }
                }
                val content = TextUtils.collapseWhitespace(cleanElement.text()).take(4000)
                if (content.length < 20) return@forEach
                val numeric = Regex("\\d+").find(id)?.value
                // Los ids de notas al pie (Fn1, Fn2...) se repiten en cada capítulo.
                // Prefijamos la clave con el capítulo para que no se pisen entre sí;
                // el anchor conserva el id original para resolver href="#Fn1".
                val isFootnote = marker.contains("footnote") ||
                    marker.contains("endnote") ||
                    marker.contains("doc-footnote") ||
                    marker.contains("doc-endnote") ||
                    tag == "aside" ||
                    id.startsWith("fn", ignoreCase = true)
                val key = if (isFootnote) "c${chapterIndex}:$id" else id
                references.add(
                    ParsedReference(
                        referenceKey = key,
                        numericKey = numeric,
                        label = numeric ?: id,
                        content = content,
                        chapterIndex = chapterIndex,
                        anchor = id
                    )
                )
            }

            body.select(
                "script, style, iframe, object, embed, form, input, button, link, meta, " +
                    "video, audio, canvas, noscript, nav, svg"
            ).remove()

            body.select("img").forEach { image ->
                val source = image.attr("src").ifBlank { image.attr("data-src") }
                if (source.isBlank()) {
                    image.remove()
                    return@forEach
                }
                val imageFile = resolveRelative(item.file.parentFile ?: extractDir, source, extractDir)
                if (imageFile == null || !imageFile.exists() || !imageFile.isFile) {
                    image.remove()
                    return@forEach
                }
                val asset = assetFor(imageFile, assetMap)
                image.attr("src", "assets/${asset.name}")
                image.removeAttr("srcset")
                image.removeAttr("data-src")
                image.removeAttr("width")
                image.removeAttr("height")
                image.removeAttr("style")
            }

            body.select("*").forEach { element ->
                val attributes = element.attributes().asList().toList()
                attributes.forEach { attribute ->
                    val key = attribute.key.lowercase()
                    if (key.startsWith("on") || key == "style" || key == "align") {
                        element.removeAttr(attribute.key)
                    }
                }
            }
            body.select("a").forEach { link ->
                link.removeAttr("target")
                link.removeAttr("style")
            }

            // Las tablas HTML se rasterizan a PNG para que el lector nativo
            // las muestre como una rejilla real (con colspan) en vez de
            // linealizarlas como texto. El texto se conserva en plainText
            // para que el índice RAG siga encontrando el contenido.
            val tableReplacements = replaceTables(body, assetMap)

            val safelist = Safelist.relaxed()
                .addTags(
                    "figure", "figcaption", "section", "article", "header", "footer", "aside",
                    "mark", "hr", "br", "sup", "sub", "small", "del", "ins", "pre", "code",
                    "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup",
                    "col", "div", "span"
                )
                .addAttributes(":all", "class", "id")
                .addAttributes("img", "src", "alt", "title")
                .addAttributes("a", "href")
                .addAttributes("td", "colspan", "rowspan")
                .addAttributes("th", "colspan", "rowspan")
                .removeProtocols("img", "src", "http", "https", "data", "ftp")
                .removeProtocols("a", "href", "http", "https", "ftp", "mailto")

            val cleanHtml = Jsoup.clean(
                body.html(),
                "",
                safelist,
                Document.OutputSettings().prettyPrint(false)
            )
            val cleanDocument = Jsoup.parse(cleanHtml)
            cleanDocument.outputSettings().prettyPrint(false)
            var plainText = cleanDocument.text()
            tableReplacements.forEach { replacement ->
                plainText = plainText.replace(replacement.token, replacement.text)
            }
            tableReplacements.forEach { replacement ->
                val paragraph = cleanDocument.select("p").firstOrNull {
                    it.text() == replacement.token
                }
                if (paragraph != null) {
                    paragraph.replaceWith(
                        Element("img")
                            .attr("src", "assets/${replacement.asset.name}")
                            .attr("alt", replacement.caption.ifBlank { "Tabla" })
                    )
                }
            }
            plainText = TextUtils.collapseWhitespace(plainText)
            val finalHtml = cleanDocument.body().html()
            val wordCount = TextUtils.wordCount(plainText)
            val hasImage = finalHtml.contains("<img", ignoreCase = true)
            if (wordCount < 3 && !hasImage) return@forEach

            val chapterTitle = tocMap[item.file.canonicalPath]
                ?: body.selectFirst("h1, h2, h3")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: document.title().trim().takeIf { it.isNotBlank() }
                ?: "Capítulo ${chapterIndex + 1}"

            chapters.add(
                ParsedChapter(
                    chapterIndex = chapterIndex,
                    title = chapterTitle,
                    pageLabel = pageLabelFor(chapterTitle, chapterIndex),
                    html = finalHtml,
                    plainText = plainText,
                    wordCount = wordCount
                )
            )
            chapterSources.add(ChapterSource(chapterIndex, item.file, finalHtml))
            chapterIndex++
        }

        if (chapters.isEmpty()) {
            throw IllegalArgumentException("No se pudo extraer texto legible del EPUB")
        }

        // Reescribe los enlaces internos del libro a un esquema propio para que
        // el lector pueda navegar entre capítulos (p. ej. la página "Contents").
        val fileToIndex = chapterSources.associate { source ->
            source.file.canonicalPath to source.index
        }
        val rewrittenChapters = chapters.mapIndexed { position, chapter ->
            val source = chapterSources[position]
            chapter.copy(
                html = rewriteInternalLinks(
                    html = source.html,
                    sourceFile = source.file,
                    chapterIndex = source.index,
                    fileToIndex = fileToIndex,
                    root = extractDir
                )
            )
        }

        val cover = findCover(opf, manifest)
        ParsedBook(
            title = title.ifBlank { "Libro sin título" },
            author = author.ifBlank { "Autor desconocido" },
            language = language,
            description = description?.takeIf { it.isNotBlank() },
            format = BookFormat.EPUB,
            chapters = rewrittenChapters,
            assets = assetMap.values.toList(),
            coverBytes = cover?.first,
            coverExtension = cover?.second,
            totalPages = chapters.size,
            references = references.distinctBy { it.referenceKey }
        )
    }

    /**
     * Extrae las referencias que el EPUB marca semánticamente y que el lector
     * necesita para resolver los enlaces internos:
     *
     * - `Aff*`: afiliaciones de autor ("Renmin University...").
     * - `ContactOfAuthor*`: datos de contacto.
     * - `Sec*`: encabezados de sección (3.4.4, 11.2...).
     * - `Fig*` / `Tab*`: figuras y tablas.
     * - `Bib*`: secciones de bibliografía.
     *
     * Las referencias de navegación (Sec/Fig/Tab/Bib) no abren la hoja de
     * referencias: el controlador las usa para hacer scroll al texto.
     */
    private fun extractSemanticReferences(
        body: Element,
        chapterIndex: Int,
        references: MutableList<ParsedReference>
    ) {
        val seen = HashSet<String>()
        body.select("[id]").forEach { element ->
            val id = element.id().trim()
            if (id.isBlank() || !seen.add(id.lowercase())) return@forEach
            when {
                AFFILIATION_ID.matches(id) -> {
                    val number = element.selectFirst(".AffiliationNumber")
                        ?.text()?.trim()
                    val content = element.selectFirst(".AffiliationText")?.text()
                        ?: element.text()
                    addSemanticReference(
                        references = references,
                        chapterIndex = chapterIndex,
                        id = id,
                        label = if (number.isNullOrBlank()) {
                            "Afiliación"
                        } else {
                            "Afiliación $number"
                        },
                        content = content
                    )
                }
                CONTACT_ID.matches(id) -> {
                    addSemanticReference(
                        references, chapterIndex, id, "Contacto", element.text()
                    )
                }
                SECTION_ID.matches(id) -> {
                    val heading = element.selectFirst("h1, h2, h3, h4, h5, h6")
                    val content = heading?.text() ?: element.text().take(200)
                    addSemanticReference(
                        references, chapterIndex, id, "Sección", content
                    )
                }
                FIGURE_ID.matches(id) -> {
                    val caption = element.selectFirst("figcaption, .Caption")?.text()
                        ?: element.text()
                    addSemanticReference(
                        references, chapterIndex, id, "Figura", caption
                    )
                }
                TABLE_ID.matches(id) -> {
                    val caption = element.selectFirst("caption, .Caption")?.text()
                        ?: element.text()
                    addSemanticReference(
                        references, chapterIndex, id, "Tabla", caption
                    )
                }
                BIBLIOGRAPHY_ID.matches(id) -> {
                    val heading = element.selectFirst("h1, h2, h3, h4, h5, h6")
                    val content = heading?.text() ?: element.text().take(200)
                    addSemanticReference(
                        references, chapterIndex, id, "Bibliografía", content
                    )
                }
            }
        }
    }

    private fun addSemanticReference(
        references: MutableList<ParsedReference>,
        chapterIndex: Int,
        id: String,
        label: String,
        content: String
    ) {
        val clean = TextUtils.collapseWhitespace(content).take(4000)
        if (clean.length < 2) return
        references.add(
            ParsedReference(
                referenceKey = "c${chapterIndex}:$id",
                numericKey = null,
                label = label,
                content = clean,
                chapterIndex = chapterIndex,
                anchor = id
            )
        )
    }

    /**
     * Sustituye cada tabla HTML por un token temporal. El token se convierte
     * en una imagen PNG después de limpiar el HTML, y su texto se inyecta en
     * plainText para que el RAG no pierda el contenido tabular.
     */
    private fun replaceTables(
        body: Element,
        assets: MutableMap<String, ParsedAsset>
    ): List<TableReplacement> {
        val replacements = mutableListOf<TableReplacement>()
        val tables = body.select("table").filter { table ->
            table.parents().none { it.tagName().equals("table", true) }
        }
        tables.forEachIndexed { index, table ->
            val asset = tableAssetFor(table, assets) ?: return@forEachIndexed
            val token = "TERMINOTABLE${index}TOKEN"
            val captionElement = table.selectFirst("caption")
            val caption = captionElement?.text()?.trim().orEmpty()
            val captionHtml = captionElement?.html()?.trim().orEmpty()
            // El texto de las celdas (sin la leyenda) se inyecta en plainText.
            // La leyenda se conserva como texto real encima de la imagen para
            // que el enlace "Table 3.1" pueda saltar a ella y sea buscable.
            val cellText = table.select("tr").text()
            val ragText = cellText.ifBlank { table.text() }
            replacements.add(TableReplacement(token, asset, ragText, caption))
            val captionBlock = Element("div").addClass("table-caption")
            if (captionHtml.isNotBlank()) {
                captionBlock.html(captionHtml)
            }
            val tokenParagraph = Element("p").text(token)
            table.replaceWith(captionBlock)
            captionBlock.after(tokenParagraph)
        }
        return replacements
    }

    private fun tableAssetFor(
        table: Element,
        assets: MutableMap<String, ParsedAsset>
    ): ParsedAsset? {
        val html = table.outerHtml()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(html.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(24)
        val key = "table:$digest"
        assets[key]?.let { return it }
        val bytes = TableBitmapRenderer.render(table) ?: return null
        if (bytes.isEmpty()) return null
        val asset = ParsedAsset(
            name = "table_$digest.png",
            mimeType = "image/png",
            bytes = bytes
        )
        assets[key] = asset
        return asset
    }

    private fun rewriteInternalLinks(
        html: String,
        sourceFile: File,
        chapterIndex: Int,
        fileToIndex: Map<String, Int>,
        root: File
    ): String {
        if (fileToIndex.isEmpty()) return html
        val document = Jsoup.parse(html)
        // Jsoup.parse re-activa el pretty-print por defecto; el resto del
        // pipeline serializa con prettyPrint(false), así que sin esto el
        // capítulo reescrito cambiaría todo su formato al guardarse.
        document.outputSettings().prettyPrint(false)
        var changed = false
        document.select("a[href]").forEach { link ->
            val href = link.attr("href").trim()
            if (href.isBlank()) return@forEach
            val lower = href.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("mailto:") || lower.startsWith("tel:") ||
                lower.startsWith("termino://")
            ) {
                return@forEach
            }
            if (href.startsWith("#")) {
                val anchor = href.removePrefix("#")
                if (anchor.isNotBlank()) {
                    link.attr("href", "termino://anchor/$chapterIndex/$anchor")
                    changed = true
                }
                return@forEach
            }
            val path = href.substringBefore('#')
            val fragment = href.substringAfter('#', "")
            if (path.isBlank()) return@forEach
            val target = resolveRelative(sourceFile.parentFile ?: root, path, root)
            val targetIndex = target?.let { fileToIndex[it.canonicalPath] }
            if (targetIndex != null) {
                val newHref = if (fragment.isNotBlank()) {
                    "termino://anchor/$targetIndex/$fragment"
                } else {
                    "termino://chapter/$targetIndex"
                }
                link.attr("href", newHref)
                changed = true
            }
        }
        document.select("[id]").forEach { element ->
            val id = element.id().trim()
            if (id.isNotBlank() && element.selectFirst("a[href^='termino://target-anchor/']") == null) {
                if (element.tagName().equals("a", ignoreCase = true)) {
                    element.before("<a href=\"termino://target-anchor/$id\">&#x200B;</a>")
                } else {
                    element.prepend("<a href=\"termino://target-anchor/$id\">&#x200B;</a>")
                }
                changed = true
            }
        }
        return if (changed) document.body().html() else html
    }

    private fun extractZip(input: InputStream, targetDir: File) {
        var totalBytes = 0L
        var entries = 0
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > 25_000) throw IllegalArgumentException("El EPUB contiene demasiados archivos")
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (entry.isDirectory || name.isBlank() || name.startsWith("__MACOSX/")) {
                    zip.closeEntry()
                    continue
                }
                if (name.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                val output = File(targetDir, name).canonicalFile
                val rootPath = targetDir.canonicalPath + File.separator
                if (!output.path.startsWith(rootPath)) {
                    zip.closeEntry()
                    continue
                }
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { stream ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > 512L * 1024L * 1024L) {
                            throw IllegalArgumentException("El EPUB supera el tamaño máximo admitido")
                        }
                        stream.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun buildTocMap(
        opf: Document,
        opfDir: File,
        manifest: Map<String, ManifestItem>,
        root: File
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()

        val navItem = manifest.values.firstOrNull {
            it.properties.split(' ', ',').any { property -> property.equals("nav", true) }
        }
        if (navItem != null) {
            val navDoc = runCatching { Jsoup.parse(navItem.file, "UTF-8", "") }.getOrNull()
            if (navDoc != null) {
                val navs = navDoc.select("nav")
                val tocNav = navs.firstOrNull { nav ->
                    nav.attr("epub:type").equals("toc", true) ||
                        nav.attr("type").equals("toc", true) ||
                        nav.className().contains("toc", true)
                } ?: navs.firstOrNull()
                tocNav?.select("a[href]")?.forEach { link ->
                    val href = link.attr("href")
                    val file = resolveRelative(navItem.file.parentFile ?: opfDir, href, root)
                    if (file != null && file.exists()) {
                        val label = link.text().trim()
                        if (label.isNotBlank()) result.putIfAbsent(file.canonicalPath, label)
                    }
                }
            }
        }

        if (result.isEmpty()) {
            val ncxId = opf.selectFirst("spine")?.attr("toc")
            val ncxItem = manifest[ncxId]
                ?: manifest.values.firstOrNull {
                    it.mediaType.equals("application/x-dtbncx+xml", true)
                }
            if (ncxItem != null) {
                val ncx = runCatching { Jsoup.parse(ncxItem.file, "UTF-8", "", Parser.xmlParser()) }.getOrNull()
                ncx?.select("navPoint")?.forEach { point ->
                    val source = point.selectFirst("content")?.attr("src")
                    val label = point.selectFirst("navLabel")?.text()?.trim()
                    if (!source.isNullOrBlank() && !label.isNullOrBlank()) {
                        val file = resolveRelative(ncxItem.file.parentFile ?: opfDir, source, root)
                        if (file != null && file.exists()) {
                            result.putIfAbsent(file.canonicalPath, label)
                        }
                    }
                }
            }
        }
        return result
    }

    private fun findCover(
        opf: Document,
        manifest: Map<String, ManifestItem>
    ): Pair<ByteArray, String>? {
        val metaCoverId = opf.select("metadata > meta").firstOrNull {
            it.attr("name").equals("cover", true)
        }?.attr("content")
        val candidates = mutableListOf<ManifestItem>()
        metaCoverId?.let { id -> manifest[id]?.let(candidates::add) }
        manifest.values.filterTo(candidates) {
            it.properties.split(' ', ',').any { property ->
                property.equals("cover-image", true)
            }
        }
        manifest.values.filterTo(candidates) {
            it.id.contains("cover", true) && it.mediaType.startsWith("image/", true)
        }
        candidates.forEach { item ->
            val extension = item.file.extension.lowercase()
            if (extension == "svg") return@forEach
            if (!item.mediaType.startsWith("image/", true)) return@forEach
            val bytes = runCatching { item.file.readBytes() }.getOrNull() ?: return@forEach
            if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) return@forEach
            return bytes to extension.ifBlank { "jpg" }
        }
        return null
    }

    /**
     * Derives the page label from the book's own visible numbering instead of
     * the spine/content position, so citations ("[Capítulo 1 · página 2]") and
     * the reader chrome match what the printed table of contents says.
     *
     * Priority:
     * 1. Explicit "Capítulo/Chapter N" in the title.
     * 2. Title that opens with a number: "1. Introduction" -> "Capítulo 1",
     *    "2.1 Background" -> "Sección 2.1".
     * 3. Position among accepted content chapters (last resort, same as
     *    before for front matter with no numbers).
     *
     * Example (EPUB "Large Language Models"): the chapter titled
     * "1. Introduction" sits at chapterIndex 8 because of front matter, so it
     * used to be cited as "Capítulo 9"; now it yields "Capítulo 1".
     */
    private fun pageLabelFor(title: String, contentPosition: Int): String {
        chapterWordAndNumber.find(title)?.let { match ->
            return "Capítulo ${match.groupValues[1]}"
        }
        leadingChapterNumber.find(title)?.let { match ->
            val number = match.groupValues[1]
            return if (number.contains('.')) "Sección $number" else "Capítulo $number"
        }
        return "Capítulo ${contentPosition + 1}"
    }

    private fun assetFor(file: File, assets: MutableMap<String, ParsedAsset>): ParsedAsset {
        val key = file.canonicalPath
        assets[key]?.let { return it }
        val originalExtension = file.extension.lowercase().ifBlank { "bin" }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(24)
        val encoded = encodeImageAsset(file, originalExtension)
        val name = "$digest.${encoded.extension}"
        val asset = ParsedAsset(
            name = name,
            mimeType = mimeFor(encoded.extension),
            bytes = encoded.bytes
        )
        assets[key] = asset
        return asset
    }

    private data class EncodedAsset(val bytes: ByteArray, val extension: String)

    /**
     * Downsamples oversized raster images (width > [MAX_ASSET_WIDTH]) using
     * BitmapFactory inSampleSize and re-encodes them: JPEG q85 for photos,
     * PNG for small graphics. SVG/GIF and already-small images pass through
     * untouched (no recompression, no quality loss). This keeps the reader's
     * bitmap decode fast and low-memory without a second copy on disk.
     */
    private fun encodeImageAsset(file: File, originalExtension: String): EncodedAsset {
        if (originalExtension !in rasterExtensions) {
            return EncodedAsset(file.readBytes(), originalExtension)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) {
            return EncodedAsset(file.readBytes(), originalExtension)
        }
        if (width <= MAX_ASSET_WIDTH && height <= MAX_ASSET_HEIGHT) {
            return EncodedAsset(file.readBytes(), originalExtension)
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(width, height)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.absolutePath, options)
        }.getOrNull() ?: return EncodedAsset(file.readBytes(), originalExtension)

        val photoLike = originalExtension in photoExtensions ||
            (originalExtension == "png" && file.length() > PHOTO_PNG_BYTES)
        val stream = ByteArrayOutputStream()
        val compressed = if (photoLike) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
        } else {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        bitmap.recycle()

        if (!compressed || stream.size() == 0) {
            return EncodedAsset(file.readBytes(), originalExtension)
        }
        return EncodedAsset(stream.toByteArray(), if (photoLike) "jpg" else "png")
    }

    /**
     * Smallest power-of-two sample size whose decoded image fits within
     * [MAX_ASSET_WIDTH] x [MAX_ASSET_HEIGHT], so both dimensions are bounded
     * even for extreme aspect ratios.
     */
    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_ASSET_WIDTH || height / sample > MAX_ASSET_HEIGHT) {
            if (sample >= MAX_SAMPLE_SIZE) break
            sample *= 2
        }
        return sample
    }

    private fun resolveRelative(baseDir: File, href: String, root: File): File? {
        val cleaned = href.substringBefore('#').substringBefore('?')
        if (cleaned.isBlank()) return null
        val decoded = runCatching { URLDecoder.decode(cleaned.replace("+", "%2B"), "UTF-8") }
            .getOrDefault(cleaned)
        val candidate = File(baseDir, decoded).canonicalFile
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.path + File.separator
        return if (candidate.path == rootCanonical.path || candidate.path.startsWith(rootPath)) {
            candidate
        } else {
            null
        }
    }

    private fun safeFile(root: File, path: String): File? {
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        val file = File(root, decoded).canonicalFile
        val rootPath = root.canonicalPath + File.separator
        return if (file.path.startsWith(rootPath)) file else null
    }

    private fun firstText(document: Document, localName: String): String? {
        val direct = document.select(localName).firstOrNull()?.text()?.trim()
        if (!direct.isNullOrBlank()) return direct
        return document.getAllElements().firstOrNull { element ->
            element.tagName().substringAfterLast(':').equals(localName, true)
        }?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun mimeFor(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "bmp" -> "image/bmp"
        else -> "application/octet-stream"
    }

    /**
     * Extrae el árbol jerárquico completo del índice (TOC) desde navigation.xhtml
     * o toc.ncx en memoria (~10ms) sin descomprimir archivos en disco.
     * Soporta niveles jerárquicos (Partes L0, Capítulos L1, Secciones L2, Subsecciones L3).
     */
    fun extractToc(bookFile: File): List<TocItem> {
        if (!bookFile.isFile) return emptyList()
        val items = mutableListOf<TocItem>()
        runCatching {
            ZipFile(bookFile).use { zip ->
                val opfEntry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".opf", ignoreCase = true) }
                    ?: return@use
                val opfDoc = Jsoup.parse(zip.getInputStream(opfEntry), "UTF-8", "", Parser.xmlParser())
                val manifest = opfDoc.select("manifest > item").associate {
                    it.attr("id") to it.attr("href")
                }
                val spineHrefs = opfDoc.select("spine > itemref").mapNotNull {
                    manifest[it.attr("idref")]
                }
                val fileToIndex = spineHrefs.mapIndexed { index, href -> href to index }.toMap()
                val filenameToIndex = spineHrefs.mapIndexed { index, href ->
                    href.substringAfterLast('/') to index
                }.toMap()

                val navEntry = zip.entries().asSequence().firstOrNull {
                    val name = it.name.lowercase()
                    name.endsWith("navigation.xhtml") || name.endsWith("nav.xhtml")
                }
                if (navEntry != null) {
                    val navDoc = Jsoup.parse(zip.getInputStream(navEntry), "UTF-8", "")
                    val tocNav = navDoc.selectFirst("nav[epub:type=toc]")
                        ?: navDoc.selectFirst("nav.toc")
                        ?: navDoc.selectFirst("nav")
                    fun walk(ol: Element, level: Int) {
                        for (li in ol.children()) {
                            if (!li.tagName().equals("li", ignoreCase = true)) continue
                            val a = li.children().firstOrNull { it.tagName().equals("a", ignoreCase = true) }
                            if (a != null) {
                                val href = a.attr("href").trim()
                                val text = a.text().trim().replace('\u00A0', ' ')
                                val path = href.substringBefore('#')
                                val fragment = href.substringAfter('#', "").ifBlank { null }
                                val idx = fileToIndex[path]
                                    ?: filenameToIndex[path.substringAfterLast('/')]
                                if (text.isNotBlank() && idx != null) {
                                    items.add(TocItem(text, idx, fragment, level))
                                }
                            }
                            val subOl = li.children().firstOrNull { it.tagName().equals("ol", ignoreCase = true) }
                            if (subOl != null) {
                                walk(subOl, level + 1)
                            }
                        }
                    }
                    val topOl = tocNav?.selectFirst("ol")
                    if (topOl != null) {
                        walk(topOl, 0)
                    }
                }

                if (items.isEmpty()) {
                    val ncxEntry = zip.entries().asSequence().firstOrNull {
                        it.name.endsWith(".ncx", ignoreCase = true)
                    }
                    if (ncxEntry != null) {
                        val ncxDoc = Jsoup.parse(zip.getInputStream(ncxEntry), "UTF-8", "", Parser.xmlParser())
                        fun walkNcx(point: Element, level: Int) {
                            val label = point.selectFirst("navLabel > text")?.text()?.trim()?.replace('\u00A0', ' ')
                            val src = point.selectFirst("content")?.attr("src")?.trim().orEmpty()
                            val path = src.substringBefore('#')
                            val fragment = src.substringAfter('#', "").ifBlank { null }
                            val idx = fileToIndex[path]
                                ?: filenameToIndex[path.substringAfterLast('/')]
                            if (!label.isNullOrBlank() && idx != null) {
                                items.add(TocItem(label, idx, fragment, level))
                            }
                            point.children().filter { it.tagName().equals("navPoint", ignoreCase = true) }.forEach {
                                walkNcx(it, level + 1)
                            }
                        }
                        ncxDoc.select("navMap > navPoint").forEach {
                            walkNcx(it, 0)
                        }
                    }
                }
            }
        }
        return items
    }
}
