package com.inkwell.notes.core.model

import kotlinx.serialization.Serializable

/** AI providers reachable with the user's own API key. Append new ones at the end. */
@Serializable
enum class AiProviderId(val displayName: String) {
    OPENAI("OpenAI"),
    ANTHROPIC("Anthropic"),
    DEEPSEEK("DeepSeek"),
    MOONSHOT("Moonshot (Kimi)"),
}

@Serializable
enum class AiCapability { TEXT, VISION, AUDIO_TRANSCRIPTION, EMBEDDINGS, REASONING }

/** Operations that can each be routed to a different model. */
@Serializable
enum class AiTask(val label: String, val required: AiCapability) {
    CHAT("Chat", AiCapability.TEXT),
    PDF_ANALYSIS("PDF analysis", AiCapability.TEXT),
    OCR_ASSIST("OCR assist (vision)", AiCapability.VISION),
    SUMMARIZE("Summaries", AiCapability.TEXT),
    MATH("Math", AiCapability.TEXT),
    TRANSLATE("Translation", AiCapability.TEXT),
    NAMING("Auto-naming", AiCapability.TEXT),
    TRANSCRIPTION("Transcription", AiCapability.AUDIO_TRANSCRIPTION),
    EMBEDDINGS("Semantic search", AiCapability.EMBEDDINGS),
}

@Serializable
data class ModelRef(val provider: AiProviderId, val modelId: String) {
    override fun toString(): String = "${provider.name}:$modelId"
}

@Serializable
data class AiProviderSettings(
    val provider: AiProviderId,
    val enabled: Boolean = true,
    /** Overrides the provider's default base URL (proxies, regional endpoints). */
    val customBaseUrl: String? = null,
    val defaultModel: String? = null,
    /** Last successful key validation, epoch ms; 0 = never validated. */
    val validatedAt: Long = 0,
)

@Serializable
data class AiPrefs(
    /** Master switch: off = no request ever leaves the device. */
    val enabled: Boolean = true,
    val providers: List<AiProviderSettings> = AiProviderId.entries.map { AiProviderSettings(it) },
    /** Model used for any task without an explicit route. */
    val defaultModel: ModelRef? = null,
    /** Per-task overrides; tasks absent here use [defaultModel]. */
    val routing: Map<AiTask, ModelRef> = emptyMap(),
    /** Ask before sending a request estimated above this many input tokens. */
    val confirmAboveTokens: Int = 40_000,
    /** Show a warning when the month's estimated spend passes this (USD). 0 = off. */
    val monthlyWarningUsd: Double = 10.0,
    /** Refuse requests once the month's estimated spend passes this (USD). 0 = off. */
    val monthlyHardLimitUsd: Double = 0.0,
    val allowImages: Boolean = true,
    /** Retrieve only relevant passages for large documents instead of sending everything. */
    val retrievalForLargeDocs: Boolean = true,
    val semanticSearch: Boolean = false,
)

/** A model's static facts. Prices are estimates in USD per million tokens. */
data class AiModelInfo(
    val ref: ModelRef,
    val displayName: String,
    val capabilities: Set<AiCapability>,
    val contextTokens: Int,
    val inputUsdPerMTok: Double? = null,
    val outputUsdPerMTok: Double? = null,
)

enum class AiRole { SYSTEM, USER, ASSISTANT }

sealed interface AiPart {
    data class Text(val text: String) : AiPart
    /** Image bytes (PNG or JPEG) for vision-capable models. */
    class Image(val bytes: ByteArray, val mimeType: String = "image/png") : AiPart
}

data class AiMessage(val role: AiRole, val parts: List<AiPart>) {
    constructor(role: AiRole, text: String) : this(role, listOf(AiPart.Text(text)))

    fun text(): String = parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.text }
}

data class AiRequest(
    val task: AiTask,
    val messages: List<AiMessage>,
    val system: String? = null,
    val maxOutputTokens: Int = 2048,
    val temperature: Float? = null,
    /** Overrides routing for this one request (e.g. model switcher in the chat panel). */
    val model: ModelRef? = null,
    /** Document the request concerns, for usage accounting. */
    val documentId: String? = null,
)

data class AiUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    /** Estimate from the local price table; NOT the provider's bill. */
    val estimatedCostUsd: Double?,
)

sealed interface AiEvent {
    data class Delta(val text: String) : AiEvent
    data class Done(val text: String, val usage: AiUsage, val model: ModelRef) : AiEvent
    data class Error(val message: String, val retryable: Boolean = false) : AiEvent
}

data class AiResult(val text: String, val usage: AiUsage, val model: ModelRef)

class AiException(message: String, val retryable: Boolean = false, cause: Throwable? = null) : Exception(message, cause)
