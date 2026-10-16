package com.inkwell.notes.ai

import android.graphics.Bitmap
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiModelInfo
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiResult
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.model.Transcript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.io.File

/**
 * Bring-your-own-key AI. Every provider (OpenAI, Anthropic, DeepSeek, Moonshot/Kimi) has its
 * own adapter for endpoints, auth headers, request format and capabilities. The core
 * note-taking experience never depends on this: callers check [isAvailable] first and
 * degrade gracefully.
 */
interface AiService {
    /** True when AI is enabled and some configured model can serve [task]. */
    fun isAvailable(task: AiTask): Boolean

    /** The model that would serve [task] (routing → default → first capable), or null. */
    fun modelFor(task: AiTask): ModelRef?

    /** Known models for a provider (static catalogue merged with the provider's model list). */
    suspend fun models(provider: AiProviderId, refresh: Boolean = false): List<AiModelInfo>

    /** Models across configured providers that have [capability]. */
    suspend fun modelsWith(capability: AiCapability): List<AiModelInfo>

    fun modelInfo(ref: ModelRef): AiModelInfo?

    /** Validates a key with a cheap authenticated call. Does not store it. */
    suspend fun validateKey(provider: AiProviderId, apiKey: String, baseUrl: String? = null): Result<String>

    /** Streams a response. Emits [AiEvent.Error] instead of throwing. */
    fun stream(request: AiRequest): Flow<AiEvent>

    /** Non-streaming convenience. Throws [AiException] on failure. */
    suspend fun complete(request: AiRequest): AiResult

    /** Rough input-token estimate for confirmation prompts and limits. */
    fun estimateTokens(request: AiRequest): Int

    /** Estimated USD for a request (input estimate + [expectedOutputTokens]); null if unknown. */
    fun estimateCostUsd(request: AiRequest, expectedOutputTokens: Int = 800): Double?

    /** Transcribes audio segments (in order) with the TRANSCRIPTION model. Null if unavailable. */
    suspend fun transcribe(recordingId: String, segments: List<File>, segmentDurationsMs: List<Long>, language: String? = null): Transcript?

    /** Embeddings for semantic search; null if no embedding model is configured. */
    suspend fun embed(texts: List<String>): List<FloatArray>?

    /** Vision OCR on a page image. Must mark unreadable text as [illegible] instead of guessing. */
    suspend fun visionTranscribe(image: Bitmap, hint: String? = null): AiResult?
}

/** SCAFFOLD STUB — replaced by the AI implementation. */
class AiServiceImpl(private val graph: AppGraph) : AiService {
    override fun isAvailable(task: AiTask): Boolean = false
    override fun modelFor(task: AiTask): ModelRef? = null
    override suspend fun models(provider: AiProviderId, refresh: Boolean): List<AiModelInfo> = emptyList()
    override suspend fun modelsWith(capability: AiCapability): List<AiModelInfo> = emptyList()
    override fun modelInfo(ref: ModelRef): AiModelInfo? = null
    override suspend fun validateKey(provider: AiProviderId, apiKey: String, baseUrl: String?): Result<String> =
        Result.failure(AiException("Not implemented"))
    override fun stream(request: AiRequest): Flow<AiEvent> = emptyFlow()
    override suspend fun complete(request: AiRequest): AiResult = throw AiException("AI is not configured")
    override fun estimateTokens(request: AiRequest): Int = 0
    override fun estimateCostUsd(request: AiRequest, expectedOutputTokens: Int): Double? = null
    override suspend fun transcribe(recordingId: String, segments: List<File>, segmentDurationsMs: List<Long>, language: String?): Transcript? = null
    override suspend fun embed(texts: List<String>): List<FloatArray>? = null
    override suspend fun visionTranscribe(image: Bitmap, hint: String?): AiResult? = null
}
