package com.termino.data.model

/** Light/dark mode of the whole app (library, settings, onboarding). */
enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

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

enum class AiTab(@androidx.annotation.StringRes val labelRes: Int) {
    CHAT(com.termino.R.string.ai_tab_chat),
    NOTES(com.termino.R.string.ai_tab_notes)
}

/**
 * Language of AI answers as a BCP-47 tag ("es", "pt-BR"...) or "auto". There
 * is no fixed list: options come from the system languages and
 * [java.util.Locale], and each name is shown in its own language.
 */
@JvmInline
value class AiLanguage(val tag: String) {
    val isAuto: Boolean get() = tag == AUTO_TAG

    val locale: java.util.Locale?
        get() = if (isAuto) null else java.util.Locale.forLanguageTag(tag)

    /** Native name ("Español", "Deutsch"), or null for Auto. */
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

        /** Migrates values of the legacy enum (SPANISH, ENGLISH...) to tags. */
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
         * Options: Auto, then the languages configured on the device (in
         * their preference order) and then every language the platform
         * knows, sorted by native name.
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
 * How much book context is sent with each question. Local models (Ollama,
 * LM Studio) and free tiers often have small windows or are slow with long
 * prompts; AUTO uses COMPACT for local servers and BALANCED otherwise.
 */
enum class ContextSize(
    val sources: Int,
    val passageChars: Int,
    val neighborSources: Int,
    val visibleChars: Int,
    val highlights: Int,
    val historyMessages: Int,
    val rewriteFollowUps: Boolean
) {
    AUTO(5, 1200, 1, 4500, 5, 8, true),
    COMPACT(3, 800, 0, 2500, 3, 4, false),
    BALANCED(5, 1200, 1, 4500, 5, 8, true),
    WIDE(6, 1600, 2, 6500, 6, 10, true);

    /** Resolves AUTO based on the server type. */
    fun resolve(localServer: Boolean): ContextSize =
        if (this == AUTO) (if (localServer) COMPACT else BALANCED) else this

    companion object {
        fun fromStorage(value: String?): ContextSize =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}
