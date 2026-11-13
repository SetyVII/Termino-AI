package com.termino.core.parser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import org.jsoup.nodes.Element

/**
 * Rasteriza una tabla HTML a PNG para que el lector nativo (TextView +
 * Spannable) la muestre como una rejilla real, con cabeceras, colspan y
 * bordes, en lugar de linealizarla como texto.
 *
 * El bitmap se dibuja a 2000 px de ancho y el lector lo escala al ancho de su
 * columna; un toque sobre la imagen abre el visor con pinch-to-zoom, así que
 * las tablas de 7-8 columnas siguen siendo legibles.
 *
 * No usa Context ni recursos: solo android.graphics, por lo que puede
 * ejecutarse dentro del parseo en segundo plano.
 */
internal object TableBitmapRenderer {

    private const val IMAGE_WIDTH = 2000
    private const val OUTER_PADDING = 12
    private const val CELL_PADDING_X = 14
    private const val CELL_PADDING_Y = 10
    private const val TEXT_SIZE = 30f
    private const val MIN_COLUMN_WIDTH = 100
    private const val MAX_COLUMN_WIDTH = 620
    private const val MIN_SHRUNK_COLUMN_WIDTH = 64
    private const val BORDER_WIDTH = 1.5f
    private const val MAX_BITMAP_HEIGHT = 8_000

    private data class Cell(
        val text: String,
        val header: Boolean,
        val colspan: Int,
        val rowspan: Int,
        val row: Int,
        val col: Int
    )

    fun render(table: Element): ByteArray? {
        val rows = collectRows(table)
        if (rows.isEmpty()) return null
        val grid = buildGrid(rows) ?: return null
        val columnCount = grid.maxOfOrNull { it.size } ?: return null
        if (columnCount == 0) return null

        // Descarta columnas completamente vacías (los EPUB técnicos suelen
        // añadir una columna espaciadora al final de cada tabla).
        val keepColumns = (0 until columnCount).filter { column ->
            grid.any { row -> row.getOrNull(column)?.text?.isNotBlank() == true }
        }
        if (keepColumns.isEmpty()) return null
        val remap = keepColumns.withIndex().associate { (newIndex, oldIndex) -> oldIndex to newIndex }
        val keptColumnCount = keepColumns.size

        val cells = grid.flatten().filterNotNull().distinct()
        if (cells.isEmpty()) return null

        val paintCache = HashMap<Boolean, TextPaint>()
        fun paintFor(header: Boolean): TextPaint = paintCache.getOrPut(header) {
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(20, 20, 20)
                textSize = TEXT_SIZE
                typeface = if (header) {
                    Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                } else {
                    Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
            }
        }

        // Ancho natural de cada columna a partir del texto sin envolver.
        val columnWidths = IntArray(keptColumnCount) { MIN_COLUMN_WIDTH }
        for (cell in cells) {
            val startColumn = remap[cell.col] ?: continue
            val span = (cell.col until cell.col + cell.colspan).count { it in remap }
            if (span <= 0) continue
            val paint = paintFor(cell.header)
            val natural = cell.text.lineSequence()
                .maxOfOrNull { line -> paint.measureText(line).toInt() } ?: 0
            val needed = (natural + CELL_PADDING_X * 2)
                .coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)
            if (span == 1) {
                columnWidths[startColumn] = max(columnWidths[startColumn], needed)
            } else {
                val perColumn = (needed / span).coerceAtLeast(MIN_COLUMN_WIDTH)
                for (offset in 0 until span) {
                    val column = startColumn + offset
                    if (column < keptColumnCount) {
                        columnWidths[column] = max(columnWidths[column], perColumn)
                    }
                }
            }
        }

        fitColumnsToWidth(columnWidths)

        // Altura de cada fila según el texto envuelto en el ancho final.
        val rowHeights = IntArray(grid.size)
        val cellLayouts = HashMap<Cell, StaticLayout>()
        for (cell in cells) {
            val startColumn = remap[cell.col] ?: continue
            val span = (cell.col until cell.col + cell.colspan).count { it in remap }
            if (span <= 0) continue
            val width = (startColumn until startColumn + span)
                .sumOf { columnWidths.getOrElse(it) { 0 } }
            val textWidth = (width - CELL_PADDING_X * 2).coerceAtLeast(20)
            val layout = StaticLayout.Builder.obtain(
                cell.text,
                0,
                cell.text.length,
                paintFor(cell.header),
                textWidth
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
            cellLayouts[cell] = layout
            val needed = layout.height + CELL_PADDING_Y * 2
            if (cell.row in rowHeights.indices) {
                rowHeights[cell.row] = max(rowHeights[cell.row], needed)
            }
        }
        for (index in rowHeights.indices) {
            if (rowHeights[index] <= 0) {
                rowHeights[index] = CELL_PADDING_Y * 2 + TEXT_SIZE.toInt()
            }
        }

        val totalHeight = OUTER_PADDING * 2 + rowHeights.sum()
        if (totalHeight <= 0 || totalHeight > MAX_BITMAP_HEIGHT) return null

        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        var y = OUTER_PADDING

        val rowTops = IntArray(grid.size)
        var runningTop = y
        for (index in grid.indices) {
            rowTops[index] = runningTop
            runningTop += rowHeights[index]
        }

        val tableLeft = OUTER_PADDING
        val headerPaint = Paint().apply { color = Color.rgb(240, 240, 240) }
        val zebraPaint = Paint().apply { color = Color.rgb(250, 250, 250) }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(185, 185, 185)
            style = Paint.Style.STROKE
            strokeWidth = BORDER_WIDTH
        }

        fun cellLeft(startColumn: Int): Int =
            tableLeft + (0 until startColumn).sumOf { columnWidths.getOrElse(it) { 0 } }

        fun cellWidth(startColumn: Int, span: Int): Int =
            (startColumn until startColumn + span).sumOf { columnWidths.getOrElse(it) { 0 } }

        fun cellBottom(cell: Cell): Int {
            val lastRow = min(cell.row + cell.rowspan, rowHeights.size) - 1
            if (lastRow < cell.row) return rowTops[cell.row] + rowHeights[cell.row]
            return rowTops[lastRow] + rowHeights[lastRow]
        }

        // Fondos (cabecera y filas alternas).
        for (cell in cells) {
            val startColumn = remap[cell.col] ?: continue
            val span = (cell.col until cell.col + cell.colspan).count { it in remap }
            if (span <= 0 || cell.row !in rowTops.indices) continue
            val left = cellLeft(startColumn).toFloat()
            val right = left + cellWidth(startColumn, span)
            val top = rowTops[cell.row].toFloat()
            val bottom = cellBottom(cell).toFloat()
            val paint = when {
                cell.header -> headerPaint
                cell.row % 2 == 0 -> zebraPaint
                else -> null
            }
            if (paint != null) canvas.drawRect(left, top, right, bottom, paint)
        }

        // Bordes por celda: una celda con colspan no dibuja líneas internas.
        for (cell in cells) {
            val startColumn = remap[cell.col] ?: continue
            val span = (cell.col until cell.col + cell.colspan).count { it in remap }
            if (span <= 0 || cell.row !in rowTops.indices) continue
            val left = cellLeft(startColumn).toFloat()
            val right = left + cellWidth(startColumn, span)
            val top = rowTops[cell.row].toFloat()
            val bottom = cellBottom(cell).toFloat()
            canvas.drawRect(left, top, right, bottom, borderPaint)
        }

        for (cell in cells) {
            val startColumn = remap[cell.col] ?: continue
            val span = (cell.col until cell.col + cell.colspan).count { it in remap }
            if (span <= 0 || cell.row !in rowTops.indices) continue
            val layout = cellLayouts[cell] ?: continue
            val left = cellLeft(startColumn) + CELL_PADDING_X
            val top = rowTops[cell.row] + CELL_PADDING_Y
            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            layout.draw(canvas)
            canvas.restore()
        }

        val stream = ByteArrayOutputStream()
        val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return if (compressed) stream.toByteArray() else null
    }

    /**
     * Reparte el ancho disponible entre las columnas: encoge las anchas de
     * forma proporcional y reparte el sobrante entre todas cuando la tabla es
     * más estrecha que el lienzo.
     */
    private fun fitColumnsToWidth(widths: IntArray) {
        if (widths.isEmpty()) return
        val available = IMAGE_WIDTH - OUTER_PADDING * 2
        var total = widths.sum()
        if (total > available) {
            val scale = available.toFloat() / total.toFloat()
            widths.indices.forEach { index ->
                widths[index] = (widths[index] * scale)
                    .toInt()
                    .coerceAtLeast(MIN_SHRUNK_COLUMN_WIDTH)
            }
            total = widths.sum()
            var excess = total - available
            for (index in widths.indices.reversed()) {
                if (excess <= 0) break
                val reducible = (widths[index] - MIN_SHRUNK_COLUMN_WIDTH).coerceAtLeast(0)
                val cut = min(excess, reducible)
                widths[index] -= cut
                excess -= cut
            }
        } else if (total < available) {
            val extra = available - total
            val base = total.coerceAtLeast(1)
            var assigned = 0
            widths.indices.forEach { index ->
                val add = if (index == widths.lastIndex) {
                    extra - assigned
                } else {
                    extra * widths[index] / base
                }
                widths[index] += add
                assigned += add
            }
        }
    }

    private fun collectRows(table: Element): List<List<Element>> {
        val rowElements = mutableListOf<Element>()
        val sections = table.children().filter {
            it.tagName().lowercase() in setOf("thead", "tbody", "tfoot")
        }
        if (sections.isNotEmpty()) {
            sections.forEach { section ->
                section.children()
                    .filter { it.tagName().equals("tr", true) }
                    .forEach(rowElements::add)
            }
        } else {
            table.children()
                .filter { it.tagName().equals("tr", true) }
                .forEach(rowElements::add)
        }
        return rowElements.map { row ->
            row.children().filter {
                it.tagName().equals("th", true) || it.tagName().equals("td", true)
            }
        }.filter { it.isNotEmpty() }
    }

    /**
     * Coloca las celdas en una rejilla resolviendo colspan/rowspan. Las celdas
     * que ocupan varias posiciones guardan la misma instancia en cada hueco,
     * pero solo se dibujan una vez, en su posición de origen.
     */
    private fun buildGrid(rows: List<List<Element>>): List<List<Cell?>>? {
        val grid = mutableListOf<MutableList<Cell?>>()
        rows.forEachIndexed { rowIndex, rowCells ->
            while (grid.size <= rowIndex) grid.add(mutableListOf())
            var column = 0
            rowCells.forEach { element ->
                while (column < grid[rowIndex].size && grid[rowIndex][column] != null) {
                    column++
                }
                val cell = Cell(
                    text = element.text().trim(),
                    header = element.tagName().equals("th", true),
                    colspan = element.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1,
                    rowspan = element.attr("rowspan").toIntOrNull()?.coerceIn(1, 80) ?: 1,
                    row = rowIndex,
                    col = column
                )
                for (rowOffset in 0 until cell.rowspan) {
                    val targetRow = rowIndex + rowOffset
                    while (grid.size <= targetRow) grid.add(mutableListOf())
                    for (columnOffset in 0 until cell.colspan) {
                        val targetColumn = column + columnOffset
                        while (grid[targetRow].size <= targetColumn) {
                            grid[targetRow].add(null)
                        }
                        if (grid[targetRow][targetColumn] == null) {
                            grid[targetRow][targetColumn] = cell
                        }
                    }
                }
                column += cell.colspan
            }
        }
        return grid
    }
}
