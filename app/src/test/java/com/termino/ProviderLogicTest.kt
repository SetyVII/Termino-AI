package com.termino

import com.termino.core.ai.ProviderEndpoint
import com.termino.core.ai.ProviderModel
import com.termino.core.ai.ProviderVerifier
import com.termino.core.ai.isLocalEndpoint
import com.termino.data.model.AiLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderLogicTest {

    @Test
    fun suggestsProviderOnlyForUnambiguousPrefixes() {
        assertEquals("anthropic", ProviderEndpoint.suggestForKey("sk-ant-api03-xyz")?.id)
        assertEquals("openrouter", ProviderEndpoint.suggestForKey("sk-or-v1-abc")?.id)
        assertEquals("gemini", ProviderEndpoint.suggestForKey("AIzaSyD")?.id)
        assertEquals("openai", ProviderEndpoint.suggestForKey("sk-proj-abc")?.id)
        // "sk-" a secas lo usan varios proveedores: no se adivina.
        assertNull(ProviderEndpoint.suggestForKey("sk-1234567890"))
        assertNull(ProviderEndpoint.suggestForKey("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"))
    }

    @Test
    fun detectsAndNormalizesUrls() {
        assertTrue(ProviderEndpoint.looksLikeUrl("http://192.168.1.20:11434"))
        assertTrue(ProviderEndpoint.looksLikeUrl("localhost:1234/v1"))
        assertTrue(ProviderEndpoint.looksLikeUrl("https://api.example.com/v1"))
        assertFalse(ProviderEndpoint.looksLikeUrl("sk-proj-abc"))
        assertEquals("http://192.168.1.20:11434", ProviderEndpoint.normalizeUrl("192.168.1.20:11434/"))
        assertEquals("https://api.example.com/v1", ProviderEndpoint.normalizeUrl("api.example.com/v1/chat/completions"))
        assertEquals("", ProviderEndpoint.normalizeUrl("").removePrefix("https://"))
    }

    @Test
    fun localEndpoints() {
        assertTrue(isLocalEndpoint("http://192.168.1.20:11434/v1"))
        assertTrue(isLocalEndpoint("http://localhost:1234/v1"))
        assertTrue(isLocalEndpoint("http://172.20.0.5/v1"))
        assertFalse(isLocalEndpoint("https://api.mistral.ai/v1"))
        assertFalse(isLocalEndpoint("http://172.40.0.5/v1"))
    }

    @Test
    fun ranksModelsFromProviderList() {
        val models = listOf(
            ProviderModel("text-embedding-3-small"),
            ProviderModel("whisper-1"),
            ProviderModel("gpt-x-mini"),
            ProviderModel("gpt-x"),
            ProviderModel("gpt-x-2025-01-01-preview"),
            ProviderModel("mistral-large-latest"),
            ProviderModel("mistral-small-latest"),
            ProviderModel("custom-ocr", isChat = false)
        )
        val chat = ProviderVerifier.rankChat(models)
        val fast = ProviderVerifier.rankFast(models)
        assertFalse(chat.any { it.contains("embedding") || it.contains("whisper") || it == "custom-ocr" })
        assertEquals("mistral-large-latest", chat.first())
        assertEquals("mistral-small-latest", fast.first())
    }

    @Test
    fun migratesLegacyLanguageValues() {
        assertEquals("es", AiLanguage.fromStorage("SPANISH").tag)
        assertTrue(AiLanguage.fromStorage(null).isAuto)
        assertTrue(AiLanguage.fromStorage("AUTO").isAuto)
        assertEquals("pt-BR", AiLanguage.fromStorage("pt-BR").tag)
        assertTrue(AiLanguage("de").instruction().contains("German"))
    }

    @Test
    fun readerFontAndThemeMigration() {
        val legacySerif = com.termino.core.reader.ReaderFont.fromStorage("SERIF")
        assertEquals("serif", legacySerif.id)
        assertEquals("sans-serif", com.termino.core.reader.ReaderFont.fromStorage("SANS").id)
        val custom = com.termino.core.reader.ReaderFont("file:Literata_Regular.ttf")
        assertTrue(custom.isCustom)
        assertEquals("Literata Regular", custom.label)
        val auto = com.termino.core.reader.ReaderTheme.AUTO
        assertEquals(com.termino.core.reader.ReaderTheme.DARK, auto.resolve(true))
        assertEquals(com.termino.core.reader.ReaderTheme.LIGHT, auto.resolve(false))
        assertEquals(com.termino.core.reader.ReaderTheme.BLACK, com.termino.core.reader.ReaderTheme.BLACK.resolve(false))
    }
}
