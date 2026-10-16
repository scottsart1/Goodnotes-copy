package com.inkwell.notes.ai.chat

import com.inkwell.notes.ai.context.SourceRef
import com.inkwell.notes.ai.net.LenientJson
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.storage.db.ChatDao
import com.inkwell.notes.core.storage.db.ChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** One persisted message of an assistant thread. */
data class ChatItem(
    val id: String,
    val threadId: String,
    val role: AiRole,
    val text: String,
    val model: ModelRef?,
    val createdAt: Long,
    /** Passages that were sent with this turn (assistant messages), for source chips. */
    val sources: List<SourceRef> = emptyList(),
)

object ChatCodec {
    private val sourcesSerializer = ListSerializer(SourceRef.serializer())

    fun encodeSources(sources: List<SourceRef>): String? =
        if (sources.isEmpty()) null else LenientJson.encodeToString(sourcesSerializer, sources)

    fun decodeSources(json: String?): List<SourceRef> =
        if (json.isNullOrBlank()) emptyList() else runCatching { LenientJson.decodeFromString(sourcesSerializer, json) }.getOrDefault(emptyList())

    /** Parses [ModelRef.toString] ("ANTHROPIC:claude-opus-5-5"). */
    fun parseModel(s: String?): ModelRef? {
        if (s.isNullOrBlank()) return null
        val i = s.indexOf(':')
        if (i <= 0 || i == s.lastIndex) return null
        val provider = runCatching { AiProviderId.valueOf(s.substring(0, i)) }.getOrNull() ?: return null
        return ModelRef(provider, s.substring(i + 1))
    }

    fun role(s: String): AiRole = when (s) {
        "assistant" -> AiRole.ASSISTANT
        "system" -> AiRole.SYSTEM
        else -> AiRole.USER
    }

    fun roleName(r: AiRole): String = when (r) {
        AiRole.ASSISTANT -> "assistant"
        AiRole.SYSTEM -> "system"
        AiRole.USER -> "user"
    }

    fun toItem(e: ChatMessageEntity) = ChatItem(e.id, e.threadId, role(e.role), e.text, parseModel(e.model), e.createdAt, decodeSources(e.sourcesJson))

    fun toEntity(item: ChatItem, documentId: String?) = ChatMessageEntity(
        id = item.id, threadId = item.threadId, documentId = documentId, role = roleName(item.role), text = item.text,
        model = item.model?.toString(), createdAt = item.createdAt, sourcesJson = encodeSources(item.sources),
    )
}

/** Assistant threads per document, persisted in Room's `chat_messages`. */
class ChatStore(private val dao: () -> ChatDao, private val clock: () -> Long = System::currentTimeMillis) {
    fun observe(threadId: String): Flow<List<ChatItem>> = dao().observeThread(threadId).map { list -> list.map(ChatCodec::toItem) }

    suspend fun latestThread(docId: String): String? = dao().threadsForDocument(docId).firstOrNull()

    suspend fun add(threadId: String, documentId: String?, role: AiRole, text: String, model: ModelRef? = null, sources: List<SourceRef> = emptyList()): ChatItem {
        // Strictly increasing timestamps keep the order stable when two messages land in one millisecond.
        val now = synchronized(this) { clock().coerceAtLeast(last + 1).also { last = it } }
        val item = ChatItem(UUID.randomUUID().toString(), threadId, role, text, model, now, sources)
        dao().upsert(ChatCodec.toEntity(item, documentId))
        return item
    }

    suspend fun delete(id: String) = dao().deleteMessage(id)

    suspend fun deleteThread(threadId: String) = dao().deleteThread(threadId)

    private var last = 0L

    companion object {
        fun newThreadId(): String = UUID.randomUUID().toString()
    }
}
