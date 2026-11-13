package com.termino.core.parser

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

object PdfCoverRenderer {

    fun render(source: File, output: File): Boolean {
        if (!source.exists()) return false
        output.parentFile?.mkdirs()
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        var bitmap: Bitmap? = null
        return try {
            descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            if (renderer.pageCount <= 0) return false
            page = renderer.openPage(0)
            val width = 720
            val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1))
                .toInt()
                .coerceAtLeast(1)
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            FileOutputStream(output).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, stream)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            page?.close()
            renderer?.close()
            descriptor?.close()
            bitmap?.recycle()
        }
    }
}
