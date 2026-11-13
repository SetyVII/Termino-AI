package com.termino.core.reader

import androidx.compose.ui.graphics.Color

/** Límites del tamaño de letra del lector (sp). */
const val MIN_FONT_SIZE = 12
const val MAX_FONT_SIZE = 40

/**
 * Subrayado que pinta el lector: solo lo que necesita el motor (rango y
 * color), sin depender de cómo lo persista cada app.
 */
data class ReaderHighlight(
    val id: String,
    val startOffset: Int,
    val endOffset: Int,
    val color: String
)

/**
 * Tema del lector. [AUTO] sigue el modo claro/oscuro de la app y se resuelve
 * a LIGHT o DARK antes de pintar; [BLACK] es negro puro (OLED).
 */
enum class ReaderTheme(val label: String) {
    AUTO("Auto"),
    LIGHT("Claro"),
    SEPIA("Sepia"),
    DARK("Oscuro"),
    BLACK("Negro");

    fun resolve(appIsDark: Boolean): ReaderTheme =
        if (this == AUTO) (if (appIsDark) DARK else LIGHT) else this

    companion object {
        fun fromStorage(value: String?): ReaderTheme =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LIGHT
    }
}

/**
 * Fuente del lector. [id] es una familia del sistema ("serif",
 * "sans-serif-condensed"...) o "file:<nombre>" para una fuente TTF/OTF que
 * el usuario importó (vive en files/fonts/).
 */
@JvmInline
value class ReaderFont(val id: String) {
    val isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)
    val fileName: String? get() = if (isCustom) id.removePrefix(CUSTOM_PREFIX) else null

    val label: String
        get() = builtIns.firstOrNull { it.first == id }?.second
            ?: fileName?.substringBeforeLast('.')?.replace('_', ' ')
            ?: id

    companion object {
        const val CUSTOM_PREFIX = "file:"
        val SERIF = ReaderFont("serif")
        val SANS = ReaderFont("sans-serif")

        /** Familias que trae Android (AOSP); si un fabricante no tiene alguna, cae a la por defecto. */
        val builtIns = listOf(
            "serif" to "Serif",
            "sans-serif" to "Sans",
            "sans-serif-light" to "Ligera",
            "sans-serif-condensed" to "Condensada",
            "sans-serif-medium" to "Media",
            "monospace" to "Mono",
            "serif-monospace" to "Máquina",
            "casual" to "Informal",
            "cursive" to "Manuscrita"
        )

        fun fromStorage(value: String?): ReaderFont = when {
            value.isNullOrBlank() -> SERIF
            value.equals("SERIF", ignoreCase = false) -> SERIF // valores del enum antiguo
            value.equals("SANS", ignoreCase = false) -> SANS
            else -> ReaderFont(value)
        }
    }
}

enum class HighlightColor(val key: String, val label: String) {
    YELLOW("yellow", "Amarillo"),
    GREEN("green", "Verde"),
    BLUE("blue", "Azul"),
    PINK("pink", "Rosa"),
    PURPLE("purple", "Morado");

    companion object {
        fun fromKey(value: String?): HighlightColor =
            entries.firstOrNull { it.key.equals(value, ignoreCase = true) } ?: YELLOW
    }
}

data class ReaderColors(
    val background: Color,
    val surface: Color,
    val foreground: Color,
    val muted: Color,
    val accent: Color,
    val highlightYellow: Color,
    val highlightGreen: Color,
    val highlightBlue: Color,
    val highlightPink: Color,
    val highlightPurple: Color,
    val isDark: Boolean
)

fun readerColors(theme: ReaderTheme): ReaderColors = when (theme) {
    // AUTO se resuelve antes de pintar; aquí solo llega en vistas previas.
    ReaderTheme.AUTO, ReaderTheme.LIGHT -> ReaderColors(
        background = Color(0xFFF7F3EA),
        surface = Color(0xFFFCF9F2),
        foreground = Color(0xFF26231F),
        muted = Color(0xFF6B6257),
        accent = Color(0xFFA9552F),
        highlightYellow = Color(0x8CFFD640),
        highlightGreen = Color(0x8C9BE59B),
        highlightBlue = Color(0x8C8FC9FF),
        highlightPink = Color(0x8CFF9EC4),
        highlightPurple = Color(0x8CC9A6FF),
        isDark = false
    )
    ReaderTheme.SEPIA -> ReaderColors(
        background = Color(0xFFF1E4C9),
        surface = Color(0xFFF7ECD6),
        foreground = Color(0xFF3B2F22),
        muted = Color(0xFF7A6852),
        accent = Color(0xFF9A5B2E),
        highlightYellow = Color(0x99F3C64E),
        highlightGreen = Color(0x998FCF8A),
        highlightBlue = Color(0x9984B8E8),
        highlightPink = Color(0x99E89AB8),
        highlightPurple = Color(0x99C0A0E8),
        isDark = false
    )
    ReaderTheme.DARK -> ReaderColors(
        background = Color(0xFF161514),
        surface = Color(0xFF1E1C1A),
        foreground = Color(0xFFE8E2D6),
        muted = Color(0xFFA79E90),
        accent = Color(0xFFD98A5F),
        highlightYellow = Color(0x99C9A227),
        highlightGreen = Color(0x9968A96B),
        highlightBlue = Color(0x99658FB5),
        highlightPink = Color(0x99B85A7A),
        highlightPurple = Color(0x999A6BB8),
        isDark = true
    )
    ReaderTheme.BLACK -> ReaderColors(
        background = Color(0xFF000000),
        surface = Color(0xFF0F0F0F),
        foreground = Color(0xFFD6D0C4),
        muted = Color(0xFF8C857A),
        accent = Color(0xFFD98A5F),
        highlightYellow = Color(0x88A88A1F),
        highlightGreen = Color(0x88508A53),
        highlightBlue = Color(0x88507496),
        highlightPink = Color(0x88964A64),
        highlightPurple = Color(0x887D5796),
        isDark = true
    )
}
