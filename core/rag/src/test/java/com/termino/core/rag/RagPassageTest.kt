package com.termino.core.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagPassageTest {
    private val filler = (1..60).joinToString(" ") { "Filler sentence number $it with no interest." }

    @Test
    fun shortChunkIsSentWhole() {
        assertEquals("Short text.", bestPassage("Short  text.", listOf("short"), 1500))
    }

    @Test
    fun picksWindowWithQueryTermsNotTheBeginning() {
        val content = "$filler Cross entropy measures the loss. Entropy is minimized during training. $filler"
        val passage = bestPassage(content, listOf("entropy", "loss"), 400)
        assertTrue(passage, passage.contains("Cross entropy"))
        assertTrue(passage.length <= 402)
        assertTrue(passage.startsWith("…"))
    }
}
