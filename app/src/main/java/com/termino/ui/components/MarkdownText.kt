package com.termino.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Carácter de cursor mostrado al final del texto mientras llega el streaming. */
private const val STREAMING_CARET = "▍"

/** Marcador interno para pipes escapados (`\|`) dentro de celdas de tabla. */
private const val ESCAPED_PIPE_PLACEHOLDER = "\u0001"

private enum class TableAlignment { LEFT, CENTER, RIGHT }

private sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String, val number: Int?, val indent: Int) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(
        val header: List<String>,
        val alignments: List<TableAlignment>,
        val rows: List<List<String>>
    ) : MarkdownBlock
    data object Rule : MarkdownBlock
}

private val headingLineRegex = Regex("^(#{1,6})\\s+(.*)$")
private val bulletLineRegex = Regex("^(\\s*)([-*•])\\s+(.+)$")
private val orderedLineRegex = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.+)$")
private val ruleLineRegex = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val tableDelimiterCellRegex = Regex("^:?-{1,}:?$")
private val numericCellRegex = Regex("^[+\\-]?[\\d.,\\s]*\\d+([.,]\\d+)?\\s*%?$")

/** Filas mínimas antes de activar el fondo alterno de la tabla. */
private const val TABLE_ZEBRA_MIN_ROWS = 4

/**
 * Ancho del degradado que desvanece el contenido en los extremos de una
 * tabla ancha, insinuando que se puede seguir desplazando en horizontal.
 */
private val tableEdgeFadeWidth = 16.dp

/**
 * Renderiza Markdown (subconjunto GFM: encabezados, listas anidadas/ordenadas,
 * citas, código, reglas, tablas con alineación y énfasis inline) con Compose puro.
 *
 * Los colores decorativos se derivan de [color] y [accentColor] (no del
 * `MaterialTheme` de la app) para garantizar contraste correcto sobre el fondo
 * del lector, que puede no coincidir con el tema claro/oscuro del sistema.
 *
 * @param streaming cuando es `true` añade un caret parpadeante al final del
 * último bloque; nunca lo duplica si el texto ya termina en `▍`.
 * @param accentColor color de acento para enlaces, marcadores y divisores.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 21.sp,
    streaming: Boolean = false,
    accentColor: Color = MaterialTheme.colorScheme.primary
) {
    val blocks = remember(markdown) { parseMarkdown(markdown) }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        blocks.forEachIndexed { blockIndex, block ->
            val caret = streaming &&
                blockIndex == blocks.lastIndex &&
                block !is MarkdownBlock.Rule
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(
                        text = block.text,
                        accent = accentColor,
                        baseColor = color,
                        caret = caret,
                        caretAlpha = streamingCaretAlpha(caret)
                    ),
                    style = TextStyle(
                        color = color,
                        fontSize = fontSize,
                        lineHeight = lineHeight
                    )
                )
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(
                        text = block.text,
                        accent = accentColor,
                        baseColor = color,
                        caret = caret,
                        caretAlpha = streamingCaretAlpha(caret)
                    ),
                    style = TextStyle(
                        color = color,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = when (block.level) {
                            1 -> 19.sp
                            2 -> 17.sp
                            3 -> 15.5.sp
                            else -> 14.5.sp
                        },
                        lineHeight = when (block.level) {
                            1 -> 24.sp
                            2 -> 22.sp
                            else -> 20.sp
                        }
                    )
                )
                is MarkdownBlock.Bullet -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (block.indent * 13).dp),
                    verticalAlignment = Alignment.Top
                ) {
                    if (block.number != null) {
                        Text(
                            text = "${block.number}.",
                            modifier = Modifier.width(26.dp),
                            color = accentColor,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End
                        )
                    } else {
                        Text(
                            text = bulletMarkerFor(block.indent),
                            modifier = Modifier.width(15.dp),
                            color = accentColor,
                            fontSize = (fontSize.value + 1f).sp,
                            lineHeight = lineHeight,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = inlineMarkdown(
                            text = block.text,
                            accent = accentColor,
                            baseColor = color,
                            caret = caret,
                            caretAlpha = streamingCaretAlpha(caret)
                        ),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            color = color,
                            fontSize = fontSize,
                            lineHeight = lineHeight
                        )
                    )
                }
                is MarkdownBlock.Quote -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = color.copy(alpha = 0.05f),
                    border = BorderStroke(1.dp, color.copy(alpha = 0.10f))
                ) {
                    Row(
                        modifier = Modifier
                            .height(IntrinsicSize.Min)
                            .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(
                                    accentColor.copy(alpha = 0.55f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = inlineMarkdown(
                                text = block.text,
                                accent = accentColor,
                                baseColor = color,
                                caret = caret,
                                caretAlpha = streamingCaretAlpha(caret)
                            ),
                            modifier = Modifier.weight(1f),
                            style = TextStyle(
                                color = color.copy(alpha = 0.88f),
                                fontSize = fontSize,
                                lineHeight = lineHeight
                            )
                        )
                    }
                }
                is MarkdownBlock.Code -> CodeBlock(
                    code = block.text,
                    color = color,
                    accent = accentColor,
                    caret = caret
                )
                is MarkdownBlock.Table -> MarkdownTable(
                    table = block,
                    color = color,
                    accent = accentColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    caret = caret
                )
                MarkdownBlock.Rule -> HorizontalDivider(
                    color = color.copy(alpha = 0.15f)
                )
            }
        }
    }
}

private fun bulletMarkerFor(indent: Int): String = when (indent) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

/**
 * Alpha animada del caret de streaming. Devuelve 1f (sin animación) cuando
 * el caret está desactivado para no correr animaciones en bloques estáticos.
 */
@Composable
private fun streamingCaretAlpha(enabled: Boolean): Float {
    if (!enabled) return 1f
    val transition = rememberInfiniteTransition(label = "streamingCaret")
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 550, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "streamingCaretAlpha"
    )
    return alpha.value
}

@Composable
private fun CodeBlock(
    code: String,
    color: Color,
    accent: Color,
    caret: Boolean
) {
    val caretAlpha = streamingCaretAlpha(caret)
    val annotated = buildAnnotatedString {
        append(code)
        if (caret) {
            withStyle(SpanStyle(color = accent.copy(alpha = caretAlpha))) {
                append(STREAMING_CARET)
            }
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.055f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.10f))
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Text(
                text = annotated,
                modifier = Modifier.padding(10.dp),
                softWrap = false,
                style = TextStyle(
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp
                )
            )
        }
    }
}

/**
 * Tabla GFM: cabecera en una sola fila con fondo sutil y negrita, divisores
 * bajo la cabecera y entre filas, columnas con peso proporcional al contenido
 * y fondo alterno sutil solo a partir de [TABLE_ZEBRA_MIN_ROWS] filas. Si la
 * tabla estimada es más ancha que el contenedor se envuelve en
 * `horizontalScroll` con anchos fijos por columna, los extremos se desvanecen
 * sutilmente para indicar que queda contenido oculto y las celdas de cabecera
 * se recortan a dos líneas con elipsis para no disparar la altura de la fila.
 */
@Composable
private fun MarkdownTable(
    table: MarkdownBlock.Table,
    color: Color,
    accent: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    caret: Boolean
) {
    val columnCount = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { row -> row.size } ?: 0
    )
    if (columnCount == 0) return

    val headerCaret = caret && table.rows.isEmpty()
    val caretAlpha = streamingCaretAlpha(caret)
    val borderColor = color.copy(alpha = 0.14f)
    val headerDividerColor = accent.copy(alpha = 0.35f)
    val rowDividerColor = color.copy(alpha = 0.10f)
    val headerBackground = accent.copy(alpha = 0.10f)
    val alternateRowBackground = color.copy(alpha = 0.04f)
    // Con pocas filas el fondo alterno se percibe como ruido; solo se
    // activa cuando la tabla tiene cierta altura.
    val zebra = table.rows.size >= TABLE_ZEBRA_MIN_ROWS

    // Columnas numéricas (50%, 1.2, -3…) se alinean a la derecha automáticamente
    // y usan cifras tabulares para que los dígitos cuadren verticalmente.
    // remember(table) evita re-escanear todas las celdas en cada recomposición.
    val effectiveAlignments = remember(table) {
        (0 until columnCount).map { columnIndex ->
            val declared = table.alignments.getOrNull(columnIndex) ?: TableAlignment.LEFT
            if (declared == TableAlignment.LEFT) {
                val bodyCells = table.rows
                    .mapNotNull { row -> row.getOrNull(columnIndex)?.trim() }
                    .filter { it.isNotEmpty() }
                val allNumeric = bodyCells.isNotEmpty() &&
                    bodyCells.all { numericCellRegex.matches(it) }
                if (allNumeric) TableAlignment.RIGHT else TableAlignment.LEFT
            } else {
                declared
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val charWidth = with(density) { fontSize.toPx().toDp() } * 0.62f
        val columnLengths = FloatArray(columnCount) { columnIndex ->
            val headerLength = table.header.getOrNull(columnIndex)?.length ?: 0
            val longestBody = table.rows.maxOfOrNull { row ->
                row.getOrNull(columnIndex)?.length ?: 0
            } ?: 0
            maxOf(headerLength, longestBody).coerceIn(3, 44).toFloat()
        }
        val fixedWidths: List<Dp> = columnLengths.map { length ->
            charWidth * length + 18.dp
        }
        val estimatedWidth = fixedWidths.fold(0.dp) { total, width -> total + width }
        val wide = estimatedWidth > maxWidth

        val tableContent: @Composable () -> Unit = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(headerBackground),
                    verticalAlignment = Alignment.Top
                ) {
                    for (columnIndex in 0 until columnCount) {
                        val isCaretCell = headerCaret && columnIndex == table.header.lastIndex
                        TableCell(
                            text = table.header.getOrNull(columnIndex) ?: "",
                            alignment = effectiveAlignments[columnIndex],
                            weight = if (wide) null else columnLengths[columnIndex].coerceAtLeast(5f),
                            fixedWidth = if (wide) fixedWidths[columnIndex] else null,
                            header = true,
                            caret = isCaretCell,
                            // En modo ancho una cabecera larguísima se recorta a
                            // 2 líneas con elipsis para no disparar la altura de
                            // la fila; la celda que lleva el caret queda libre
                            // para que el streaming siempre sea visible. En
                            // modo ajustado el soft-wrap decide solo.
                            maxLines = if (wide && !isCaretCell) 2 else Int.MAX_VALUE,
                            overflow = if (wide && !isCaretCell) TextOverflow.Ellipsis else TextOverflow.Clip,
                            color = color,
                            accent = accent,
                            fontSize = fontSize,
                            lineHeight = lineHeight,
                            caretAlpha = caretAlpha
                        )
                    }
                }
                HorizontalDivider(color = headerDividerColor, thickness = 1.5.dp)
                table.rows.forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) {
                        HorizontalDivider(color = rowDividerColor)
                    }
                    val isLastRow = rowIndex == table.rows.lastIndex
                    val lastCellIndex = (row.size - 1).coerceAtLeast(0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (zebra && rowIndex % 2 == 1) alternateRowBackground else Color.Transparent
                            ),
                        verticalAlignment = Alignment.Top
                    ) {
                        for (columnIndex in 0 until columnCount) {
                            TableCell(
                                text = row.getOrNull(columnIndex) ?: "",
                                alignment = effectiveAlignments[columnIndex],
                                weight = if (wide) null else columnLengths[columnIndex].coerceAtLeast(5f),
                                fixedWidth = if (wide) fixedWidths[columnIndex] else null,
                                header = false,
                                caret = caret && isLastRow && columnIndex == lastCellIndex,
                                color = color,
                                accent = accent,
                                fontSize = fontSize,
                                lineHeight = lineHeight,
                                caretAlpha = caretAlpha
                            )
                        }
                    }
                }
            }
        }

        if (wide) {
            val scrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Capa offscreen: el degradado de los extremos borra el
                    // contenido con BlendMode.DstIn sin agujerear el fondo del
                    // lector que hay detrás de la tabla.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val fadeWidth = minOf(tableEdgeFadeWidth.toPx(), size.width / 3f)
                        if (fadeWidth > 0f) {
                            // Cada extremo se desvanece en proporción a lo que
                            // queda oculto en ese lado: al abrir la tabla solo
                            // se insinúa el borde derecho, y al llegar al final
                            // solo queda el izquierdo.
                            val leftAlpha = (scrollState.value.toFloat() / fadeWidth)
                                .coerceIn(0f, 1f)
                            if (leftAlpha > 0f) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = leftAlpha)
                                        ),
                                        startX = 0f,
                                        endX = fadeWidth
                                    ),
                                    topLeft = Offset.Zero,
                                    size = Size(fadeWidth, size.height),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                            val rightAlpha =
                                ((scrollState.maxValue - scrollState.value).toFloat() / fadeWidth)
                                    .coerceIn(0f, 1f)
                            if (rightAlpha > 0f) {
                                drawRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = rightAlpha),
                                            Color.Transparent
                                        ),
                                        startX = size.width - fadeWidth,
                                        endX = size.width
                                    ),
                                    topLeft = Offset(size.width - fadeWidth, 0f),
                                    size = Size(fadeWidth, size.height),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                        }
                    }
                    .horizontalScroll(scrollState)
            ) {
                TableSurface(borderColor = borderColor, content = tableContent)
            }
        } else {
            TableSurface(borderColor = borderColor, content = tableContent)
        }
    }
}

@Composable
private fun TableSurface(
    borderColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, borderColor)
    ) {
        content()
    }
}

/**
 * Celda de tabla: padding cómodo, alineación declarada (o detectada para
 * columnas numéricas) y recorte opcional a [maxLines] con elipsis, reservado
 * a las cabeceras de tablas anchas con scroll.
 */
@Composable
private fun RowScope.TableCell(
    text: String,
    alignment: TableAlignment,
    weight: Float?,
    fixedWidth: Dp?,
    header: Boolean,
    caret: Boolean,
    color: Color,
    accent: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    caretAlpha: Float,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val widthModifier = when {
        weight != null -> Modifier.weight(weight)
        fixedWidth != null -> Modifier.width(fixedWidth)
        else -> Modifier
    }
    Text(
        text = inlineMarkdown(
            text = text,
            accent = accent,
            baseColor = color,
            caret = caret,
            caretAlpha = caretAlpha
        ),
        modifier = widthModifier.padding(
            horizontal = 12.dp,
            vertical = 8.dp
        ),
        textAlign = when (alignment) {
            TableAlignment.LEFT -> TextAlign.Left
            TableAlignment.CENTER -> TextAlign.Center
            TableAlignment.RIGHT -> TextAlign.Right
        },
        maxLines = maxLines,
        overflow = overflow,
        style = TextStyle(
            color = if (header) color else color.copy(alpha = 0.94f),
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = if (header) FontWeight.SemiBold else null,
            fontFeatureSettings = if (alignment == TableAlignment.RIGHT) "tnum" else null
        )
    )
}

private fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    if (markdown.isBlank()) return emptyList()
    val lines = markdown
        .replace("\r\n", "\n")
        .replace("\t", "    ")
        .split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val raw = lines[index]
        val line = raw.trim()
        if (line.isEmpty()) {
            index++
            continue
        }
        if (line.startsWith("```")) {
            val code = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trim().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index++
            }
            if (index < lines.size) index++
            blocks.add(MarkdownBlock.Code(code.toString()))
            continue
        }
        val headingMatch = headingLineRegex.find(line)
        if (headingMatch != null) {
            blocks.add(
                MarkdownBlock.Heading(
                    level = headingMatch.groupValues[1].length,
                    text = headingMatch.groupValues[2].trim()
                )
            )
            index++
            continue
        }
        // Tabla GFM: fila de cabecera con pipes + línea separadora de guiones.
        // Si el separador aún no llegó (streaming), esto cae al caso de
        // párrafo y se muestra como texto plano sin romper el layout.
        if (line.contains("|") &&
            index + 1 < lines.size &&
            isTableDelimiterRow(lines[index + 1])
        ) {
            val header = splitTableRow(line)
            val alignmentCells = splitTableRow(lines[index + 1])
            val alignments = List(header.size) { columnIndex ->
                tableAlignment(alignmentCells.getOrNull(columnIndex) ?: "")
            }
            index += 2
            val rows = mutableListOf<List<String>>()
            while (index < lines.size) {
                val rowLine = lines[index]
                val rowTrim = rowLine.trim()
                if (rowTrim.isEmpty() || !rowLine.contains("|")) break
                if (rowTrim.startsWith("```") || rowTrim.startsWith("#")) break
                // Evita tragarse la cabecera de una tabla consecutiva.
                if (lines.getOrNull(index + 1)?.let { isTableDelimiterRow(it) } == true) break
                rows.add(splitTableRow(rowLine))
                index++
            }
            blocks.add(MarkdownBlock.Table(header, alignments, rows))
            continue
        }
        val bulletMatch = bulletLineRegex.find(raw)
        if (bulletMatch != null) {
            val indent = (bulletMatch.groupValues[1].length / 2).coerceAtMost(4)
            val text = StringBuilder(bulletMatch.groupValues[3].trim())
            index++
            index = appendContinuationLines(lines, index, text)
            blocks.add(MarkdownBlock.Bullet(text.toString(), null, indent))
            continue
        }
        val orderedMatch = orderedLineRegex.find(raw)
        if (orderedMatch != null) {
            val indent = (orderedMatch.groupValues[1].length / 2).coerceAtMost(4)
            val text = StringBuilder(orderedMatch.groupValues[3].trim())
            index++
            index = appendContinuationLines(lines, index, text)
            blocks.add(
                MarkdownBlock.Bullet(
                    text = text.toString(),
                    number = orderedMatch.groupValues[2].toIntOrNull(),
                    indent = indent
                )
            )
            continue
        }
        if (line.startsWith(">")) {
            val quote = StringBuilder()
            while (index < lines.size && lines[index].trim().startsWith(">")) {
                val quoteLine = lines[index].trim().removePrefix(">").trim()
                if (quote.isNotEmpty()) quote.append(' ')
                quote.append(quoteLine)
                index++
            }
            blocks.add(MarkdownBlock.Quote(quote.toString()))
            continue
        }
        if (ruleLineRegex.matches(line)) {
            blocks.add(MarkdownBlock.Rule)
            index++
            continue
        }
        val paragraph = StringBuilder(line)
        index++
        while (index < lines.size) {
            val next = lines[index].trim()
            val nextStartsTable = next.contains("|") &&
                lines.getOrNull(index + 1)?.let { isTableDelimiterRow(it) } == true
            if (next.isEmpty() ||
                nextStartsTable ||
                next.startsWith("#") ||
                next.startsWith(">") ||
                next.startsWith("```") ||
                bulletLineRegex.matches(next) ||
                orderedLineRegex.matches(next) ||
                ruleLineRegex.matches(next)
            ) {
                break
            }
            paragraph.append(' ').append(next)
            index++
        }
        blocks.add(MarkdownBlock.Paragraph(paragraph.toString()))
    }
    return blocks
}

/**
 * Absorbe líneas de continuación indentadas de un elemento de lista para que
 * el texto envuelto de un bullet no se convierta en párrafos sueltos.
 */
private fun appendContinuationLines(
    lines: List<String>,
    startIndex: Int,
    target: StringBuilder
): Int {
    var index = startIndex
    while (index < lines.size) {
        val rawLine = lines[index]
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) break
        val indented = rawLine.startsWith("  ")
        val isMarker = bulletLineRegex.matches(rawLine) || orderedLineRegex.matches(rawLine)
        if (!indented || isMarker) break
        if (trimmed.startsWith("#") || trimmed.startsWith("```")) break
        if (target.isNotEmpty()) target.append(' ')
        target.append(trimmed)
        index++
    }
    return index
}

private fun isTableDelimiterRow(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains("|")) return false
    val cells = splitTableRow(trimmed)
    if (cells.isEmpty()) return false
    return cells.all { cell -> cell.isNotEmpty() && tableDelimiterCellRegex.matches(cell) }
}

private fun splitTableRow(line: String): List<String> {
    var content = line.trim().replace("\\|", ESCAPED_PIPE_PLACEHOLDER)
    if (content.startsWith("|")) content = content.substring(1)
    if (content.endsWith("|")) content = content.substring(0, content.length - 1)
    return content.split("|").map { cell ->
        cell.trim().replace(ESCAPED_PIPE_PLACEHOLDER, "|")
    }
}

private fun tableAlignment(cell: String): TableAlignment {
    val startsWithColon = cell.startsWith(":")
    val endsWithColon = cell.endsWith(":")
    return when {
        startsWithColon && endsWithColon -> TableAlignment.CENTER
        endsWithColon -> TableAlignment.RIGHT
        else -> TableAlignment.LEFT
    }
}

private fun inlineMarkdown(
    text: String,
    accent: Color,
    baseColor: Color,
    caret: Boolean,
    caretAlpha: Float
): AnnotatedString {
    val codeBackground = baseColor.copy(alpha = 0.08f)
    // Nunca duplica el caret: si el texto ya termina en ▍ se reemplaza por
    // uno solo con estilo (y parpadeo) controlado.
    val body = if (caret && text.endsWith(STREAMING_CARET)) {
        text.dropLast(STREAMING_CARET.length)
    } else {
        text
    }
    val tokenRegex = Regex(
        "(\\*\\*[^*]+\\*\\*|__[^_]+__|~~[^~\\n]+~~|\\*[^*\\n]+\\*|_[^_\\n]+_|`[^`]+`|\\[[^\\]]+]\\([^)]+\\))"
    )
    return buildAnnotatedString {
        var cursor = 0
        tokenRegex.findAll(body).forEach { match ->
            if (match.range.first > cursor) {
                append(body.substring(cursor, match.range.first))
            }
            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("__") && token.endsWith("__") -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("~~") && token.endsWith("~~") -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(token.substring(2, token.length - 2))
                    }
                }
                token.startsWith("`") && token.endsWith("`") -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                            color = baseColor
                        )
                    ) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("[") -> {
                    val close = token.indexOf(']')
                    val openParen = token.indexOf('(', close)
                    val label = if (close > 1) token.substring(1, close) else token
                    if (openParen > 0) {
                        withStyle(
                            SpanStyle(
                                color = accent,
                                textDecoration = TextDecoration.Underline
                            )
                        ) {
                            append(label)
                        }
                    } else {
                        append(token)
                    }
                }
                token.startsWith("*") && token.endsWith("*") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                token.startsWith("_") && token.endsWith("_") -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(token.substring(1, token.length - 1))
                    }
                }
                else -> append(token)
            }
            cursor = match.range.last + 1
        }
        if (cursor < body.length) {
            append(body.substring(cursor))
        }
        if (caret) {
            withStyle(SpanStyle(color = accent.copy(alpha = caretAlpha))) {
                append(STREAMING_CARET)
            }
        }
    }
}
