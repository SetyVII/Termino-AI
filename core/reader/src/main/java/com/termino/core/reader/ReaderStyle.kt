package com.termino.core.reader

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource

/** Reader font size bounds (sp). */
const val MIN_FONT_SIZE = 12
const val MAX_FONT_SIZE = 40

/**
 * A highlight drawn by the reader: only what the engine needs (range and
 * color), independent of how each app persists it.
 */
data class ReaderHighlight(
    val id: String,
    val startOffset: Int,
    val endOffset: Int,
    val color: String
)

/**
 * Reader theme. [AUTO] follows the app's light/dark mode and is resolved to
 * LIGHT or DARK before drawing; [BLACK] is pure black (OLED).
 */
enum class ReaderTheme(@StringRes val labelRes: Int) {
    AUTO(R.string.reader_theme_auto),
    LIGHT(R.string.reader_theme_light),
    SEPIA(R.string.reader_theme_sepia),
    DARK(R.string.reader_theme_dark),
    BLACK(R.string.reader_theme_black);

    fun resolve(appIsDark: Boolean): ReaderTheme =
        if (this == AUTO) (if (appIsDark) DARK else LIGHT) else this

    companion object {
        fun fromStorage(value: String?): ReaderTheme =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LIGHT
    }
}

/**
 * Reader font. [id] is a system family ("serif", "sans-serif-condensed"...)
 * or "file:<name>" for a TTF/OTF font imported by the user (stored in
 * files/fonts/).
 */
@JvmInline
value class ReaderFont(val id: String) {
    val isCustom: Boolean get() = id.startsWith(CUSTOM_PREFIX)
    val fileName: String? get() = if (isCustom) id.removePrefix(CUSTOM_PREFIX) else null

    /** String resource for built-in fonts, null for imported ones. */
    @get:StringRes
    val labelRes: Int?
        get() = builtIns.firstOrNull { it.first == id }?.second

    /** Name of an imported font (derived from its file name), or the raw id. */
    val label: String
        get() = fileName?.substringBeforeLast('.')?.replace('_', ' ') ?: id

    companion object {
        const val CUSTOM_PREFIX = "file:"
        val SERIF = ReaderFont("serif")
        val SANS = ReaderFont("sans-serif")

        /** Families shipped with Android (AOSP); missing ones fall back to the default. */
        val builtIns = listOf(
            "serif" to R.string.reader_font_serif,
            "sans-serif" to R.string.reader_font_sans,
            "sans-serif-light" to R.string.reader_font_light,
            "sans-serif-condensed" to R.string.reader_font_condensed,
            "sans-serif-medium" to R.string.reader_font_medium,
            "monospace" to R.string.reader_font_mono,
            "serif-monospace" to R.string.reader_font_typewriter,
            "casual" to R.string.reader_font_casual,
            "cursive" to R.string.reader_font_cursive
        )

        fun fromStorage(value: String?): ReaderFont = when {
            value.isNullOrBlank() -> SERIF
            value.equals("SERIF", ignoreCase = false) -> SERIF // legacy enum values
            value.equals("SANS", ignoreCase = false) -> SANS
            else -> ReaderFont(value)
        }
    }
}

/** Localized name of the font: the resource for built-ins, the file name for imported fonts. */
@Composable
fun ReaderFont.displayName(): String = labelRes?.let { stringResource(it) } ?: label

enum class HighlightColor(val key: String, @StringRes val labelRes: Int) {
    YELLOW("yellow", R.string.reader_color_yellow),
    GREEN("green", R.string.reader_color_green),
    BLUE("blue", R.string.reader_color_blue),
    PINK("pink", R.string.reader_color_pink),
    PURPLE("purple", R.string.reader_color_purple);

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
    // AUTO is resolved before drawing; it only reaches here in previews.
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
