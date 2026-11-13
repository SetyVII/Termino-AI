package com.termino.data.fonts

import com.termino.R
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
 * Fonts available to the reader: system families plus TTF/OTF files the user
 * imports (copied to files/fonts so they do not depend on permission to the
 * original file).
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
            file != null -> Typeface.SERIF // imported font that no longer exists
            else -> Typeface.create(font.id, Typeface.NORMAL)
        }
    }

    /** Copies and validates a font picked with the file picker. */
    suspend fun import(uri: Uri): Result<ReaderFont> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: "fuente.ttf"
            val ext = displayName.substringAfterLast('.', "ttf").lowercase()
            require(ext in EXTENSIONS) { appContext.getString(R.string.font_error_type) }
            val safeName = displayName.substringBeforeLast('.')
                .replace(Regex("[^A-Za-z0-9 _-]"), "_").take(60).ifBlank { "fuente" } + "." + ext
            val target = File(dir, safeName)
            val temp = File(dir, "$safeName.tmp")
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: error(appContext.getString(R.string.font_error_read))
            // Typeface throws if the file is not a valid font.
            runCatching { Typeface.createFromFile(temp) }
                .getOrElse { temp.delete(); error(appContext.getString(R.string.font_error_invalid)) }
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
