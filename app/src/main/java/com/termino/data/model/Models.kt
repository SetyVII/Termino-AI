package com.termino.data.model

/** Modo claro/oscuro de toda la app (biblioteca, ajustes, onboarding). */
enum class AppThemeMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Oscuro");

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

enum class AiPaneMode {
    DOCKED,
    FLOATING,
    MINIMIZED;

    companion object {
        fun fromStorage(value: String?): AiPaneMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DOCKED
    }
}

enum class AiTab(val label: String) {
    CHAT("Chat"),
    NOTES("Notas")
}

/**
 * Idioma de las respuestas de la IA como etiqueta BCP-47 ("es", "pt-BR"...)
 * o "auto". No hay lista cerrada: las opciones salen de los idiomas del
 * sistema y de [java.util.Locale], y el nombre se muestra en su propio idioma.
 */
@JvmInline
value class AiLanguage(val tag: String) {
    val isAuto: Boolean get() = tag == AUTO_TAG

    val locale: java.util.Locale?
        get() = if (isAuto) null else java.util.Locale.forLanguageTag(tag)

    /** Nombre nativo ("Español", "Deutsch"), o null para Auto. */
    val nativeName: String?
        get() = locale?.let { loc ->
            loc.getDisplayName(loc).replaceFirstChar { it.titlecase(loc) }
        }

    fun instruction(): String {
        val loc = locale ?: return "Answer in the same language as the user's question " +
            "or, if unclear, the language of the passage being read."
        val english = loc.getDisplayName(java.util.Locale.ENGLISH)
        return "Always answer in $english ($nativeName), regardless of the language " +
            "of the question or of the book."
    }

    companion object {
        const val AUTO_TAG = "auto"
        val AUTO = AiLanguage(AUTO_TAG)

        /** Migra los valores del antiguo enum (SPANISH, ENGLISH...) a etiquetas. */
        private val legacy = mapOf(
            "AUTO" to AUTO_TAG, "SPANISH" to "es", "ENGLISH" to "en", "FRENCH" to "fr",
            "GERMAN" to "de", "PORTUGUESE" to "pt", "ITALIAN" to "it"
        )

        fun fromStorage(value: String?): AiLanguage {
            if (value.isNullOrBlank()) return AUTO
            legacy[value.uppercase()]?.let { return AiLanguage(it) }
            if (value.equals(AUTO_TAG, ignoreCase = true)) return AUTO
            val loc = java.util.Locale.forLanguageTag(value)
            return if (loc.language.isNullOrBlank()) AUTO else AiLanguage(loc.toLanguageTag())
        }

        /**
         * Opciones: Auto, luego los idiomas configurados en el dispositivo
         * (en su orden de preferencia) y después todos los idiomas que
         * conoce la plataforma, ordenados por nombre nativo.
         */
        fun options(): List<AiLanguage> {
            val system = android.content.res.Resources.getSystem().configuration.locales
            val preferred = (0 until system.size()).map { AiLanguage(system[it].language) }
            val all = java.util.Locale.getISOLanguages()
                .map { java.util.Locale.forLanguageTag(it) }
                .filter { loc -> loc.getDisplayLanguage(loc).let { it.isNotBlank() && it != loc.language } }
                .map { AiLanguage(it.language) }
                .sortedBy { it.nativeName?.lowercase() }
            return (listOf(AUTO) + preferred + all).distinctBy { it.tag }
        }
    }
}

enum class QuickActionType {
    DICTIONARY,
    EXPLAIN
}

enum class ChatRole(val storage: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromStorage(value: String?): ChatRole =
            entries.firstOrNull { it.storage.equals(value, ignoreCase = true) } ?: ASSISTANT
    }
}

enum class ImportJobState {
    QUEUED,
    RUNNING,
    SUCCESS,
    ERROR
}

data class ImportJob(
    val id: String,
    val displayName: String,
    val progress: Int = 0,
    val state: ImportJobState = ImportJobState.QUEUED,
    val message: String? = null,
    val bookId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)


/**
 * Cuánto contexto del libro se envía en cada pregunta. Los modelos locales
 * (Ollama, LM Studio) y los gratuitos suelen tener ventanas pequeñas o ser
 * lentos con prompts largos; AUTO usa COMPACT para servidores locales y
 * BALANCED para el resto.
 */
enum class ContextSize(
    val label: String,
    val sources: Int,
    val passageChars: Int,
    val neighborSources: Int,
    val visibleChars: Int,
    val highlights: Int,
    val historyMessages: Int,
    val rewriteFollowUps: Boolean
) {
    AUTO("Automático", 5, 1200, 1, 4500, 5, 8, true),
    COMPACT("Compacto", 3, 800, 0, 2500, 3, 4, false),
    BALANCED("Equilibrado", 5, 1200, 1, 4500, 5, 8, true),
    WIDE("Amplio", 6, 1600, 2, 6500, 6, 10, true);

    /** Resuelve AUTO según el tipo de servidor. */
    fun resolve(localServer: Boolean): ContextSize =
        if (this == AUTO) (if (localServer) COMPACT else BALANCED) else this

    companion object {
        fun fromStorage(value: String?): ContextSize =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}
