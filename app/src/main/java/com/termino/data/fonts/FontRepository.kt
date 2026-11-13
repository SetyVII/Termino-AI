package com.termino.data.fonts

import com.termino.core.reader.ReaderFont
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Fuentes disponibles para el lector: las familias del sistema y las TTF/OTF
 * que el usuario importe (se copian a files/fonts para no depender del
 * permiso sobre el archivo original).
 */
class FontRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "fonts").apply { mkdirs() }
    private val cache = ConcurrentHashMap<String, Typeface>()

    private val _fonts = MutableStateFlow(scan())
    val fonts: StateFlow<List<ReaderFont>> = _fonts.asStateFlow()

    private fun scan(): List<ReaderFont> {
        val custom = dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.map { ReaderFont(ReaderFont.CUSTOM_PREFIX + it.name) }
            .orEmpty()
        return ReaderFont.builtIns.map { ReaderFont(it.first) } + custom
    }

    fun typeface(font: ReaderFont): Typeface = cache.getOrPut(font.id) {
        val file = font.fileName?.let { File(dir, it) }
        when {
            file != null && file.exists() ->
                runCatching { Typeface.createFromFile(file) }.getOrDefault(Typeface.SERIF)
            file != null -> Typeface.SERIF // fuente importada que ya no existe
            else -> Typeface.create(font.id, Typeface.NORMAL)
        }
    }

    /** Copia y valida una fuente elegida con el selector de archivos. */
    suspend fun import(uri: Uri): Result<ReaderFont> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "fuente.ttf"
            val ext = displayName.substringAfterLast('.', "ttf").lowercase()
            require(ext in EXTENSIONS) { "Solo se admiten fuentes .ttf u .otf" }
            val safeName = displayName.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9 _-]"), "_").take(60).ifBlank { "fuente" } + "." + ext
            val target = File(dir, safeName)
            val temp = File(dir, "$safeName.tmp")
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: error("No se pudo leer el archivo")
            // Typeface lanza si el archivo no es una fuente válida.
            runCatching { Typeface.createFromFile(temp) }
                .getOrElse { temp.delete(); error("El archivo no es una fuente válida") }
            if (target.exists()) target.delete()
            temp.renameTo(target)
            val font = ReaderFont(ReaderFont.CUSTOM_PREFIX + target.name)
            cache.remove(font.id)
            _fonts.value = scan()
            font
        }
    }

    fun delete(font: ReaderFont) {
        val name = font.fileName ?: return
        File(dir, name).delete()
        cache.remove(font.id)
        _fonts.value = scan()
    }

    private companion object {
        val EXTENSIONS = setOf("ttf", "otf")
    }
}
