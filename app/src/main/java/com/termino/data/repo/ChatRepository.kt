package com.termino.data.repo

import com.termino.core.ai.AiMessage
import com.termino.ai.SourceCodec
import com.termino.ai.SourceRef
import com.termino.data.db.ChatDao
import com.termino.data.db.ChatMessageEntity
import com.termino.data.model.ChatRole
import kotlinx.coroutines.flow.Flow

class ChatRepository(
    private val dao: ChatDao
) {
    fun observeMessages(bookId: String): Flow<List<ChatMessageEntity>> =
        dao.observeForBook(bookId)

    suspend fun addUserMessage(bookId: String, content: String): Long =
        dao.insert(
            ChatMessageEntity(
                bookId = bookId,
                role = ChatRole.USER.storage,
                content = content,
                sourcesJson = null,
                model = null,
                isError = false,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun addAssistantMessage(
        bookId: String,
        content: String,
        sources: List<SourceRef>,
        model: String,
        isError: Boolean = false
    ): Long = dao.insert(
        ChatMessageEntity(
            bookId = bookId,
            role = ChatRole.ASSISTANT.storage,
            content = content,
            sourcesJson = if (sources.isEmpty()) null else SourceCodec.encode(sources),
            model = model,
            isError = isError,
            createdAt = System.currentTimeMillis()
        )
    )

    suspend fun getRecent(bookId: String, limit: Int = 10): List<ChatMessageEntity> =
        dao.getRecent(bookId, limit).reversed()

    suspend fun clear(bookId: String) = dao.deleteByBook(bookId)

    fun toAiMessages(messages: List<ChatMessageEntity>): List<AiMessage> = messages
        .filter { !it.isError }
        .mapNotNull { message ->
            val role = when (ChatRole.fromStorage(message.role)) {
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
                ChatRole.SYSTEM -> "system"
            }
            message.content.takeIf { it.isNotBlank() }?.let { AiMessage(role, it) }
        }
}
