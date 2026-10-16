package com.inkwell.notes.ai.provider

import com.inkwell.notes.ai.net.ErrorMapper
import com.inkwell.notes.ai.net.LenientJson
import com.inkwell.notes.ai.net.SseEvent
import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiException
import com.inkwell.notes.core.model.AiMessage
import com.inkwell.notes.core.model.AiPart
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.TranscriptSegment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray
internal fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString || it.contentOrNull != null }?.contentOrNull
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
internal fun parseObject(text: String): JsonObject? = runCatching { LenientJson.parseToJsonElement(text).jsonObject }.getOrNull()

internal fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Folds SYSTEM turns into the system prompt; returns (system, conversation without system turns). */
internal fun splitSystem(system: String?, messages: List<AiMessage>): Pair<String?, List<AiMessage>> {
    val sys = buildList {
        system?.takeIf { it.isNotBlank() }?.let(::add)
        messages.filter { it.role == AiRole.SYSTEM }.map { it.text() }.filter { it.isNotBlank() }.forEach(::add)
    }.joinToString("\n\n").takeIf { it.isNotBlank() }
    return sys to messages.filter { it.role != AiRole.SYSTEM }
}

/**
 * Chat Completions wire format shared by OpenAI, DeepSeek and Moonshot: Bearer auth,
 * `POST /chat/completions` with `stream: true`, `GET /models`.
 */
abstract class OpenAiCompatibleAdapter : ProviderAdapter {
    /** Sends `stream_options.include_usage` (OpenAI, DeepSeek). Moonshot reports usage in the final choice. */
    protected open val sendsStreamOptions: Boolean = true

    /** True: the model takes `max_completion_tokens` (reasoning models) instead of `max_tokens`. */
    protected open fun usesCompletionTokens(model: ModelSpec): Boolean = false

    /** Role used for the system prompt, or null to prepend it to the first user turn. */
    protected open fun systemRole(model: ModelSpec): String? = "system"

    protected open fun supportsTemperature(model: ModelSpec): Boolean = !model.has(AiCapability.REASONING)

    /** `reasoning_effort` value for this call, or null to omit it. */
    protected open fun reasoningEffort(call: ChatCall): String? = null

    protected open fun clampTemperature(t: Float): Float = t.coerceIn(0f, 2f)

    override fun isListed(spec: ModelSpec, all: Collection<String>): Boolean = spec.capabilities.isNotEmpty()

    override fun modelsRequest(baseUrl: String, apiKey: String): Request =
        Request.Builder().url("$baseUrl/models").get()
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()

    override fun parseModels(body: String): List<ModelSpec> {
        val data = parseObject(body)?.get("data").arr() ?: return emptyList()
        return data.mapNotNull { it.obj()?.get("id").str() }.distinct().map { spec(it).copy(live = true) }
    }

    /** Output budget: reasoning models need room for their hidden reasoning tokens. */
    override fun outputBudget(model: ModelSpec, requested: Int): Int {
        val wanted = if (model.has(AiCapability.REASONING)) {
            if (requested < 2048) 4096 else maxOf(requested, 16_000)
        } else requested
        return wanted.coerceAtMost(model.maxOutputTokens).coerceAtLeast(1)
    }

    private fun maxTokensParam(call: ChatCall): String {
        val completion = usesCompletionTokens(call.model) xor (call.variant and VARIANT_SWAP_MAX_TOKENS != 0)
        return if (completion) "max_completion_tokens" else "max_tokens"
    }

    override fun chatBody(call: ChatCall): JsonObject {
        val (system, turns) = splitSystem(call.system, call.messages)
        val role = systemRole(call.model)
        val vision = call.model.has(AiCapability.VISION)
        return buildJsonObject {
            put("model", call.model.id)
            putJsonArray("messages") {
                if (system != null && role != null) addJsonObject { put("role", role); put("content", system) }
                var prepend = if (role == null) system else null
                for (m in turns) {
                    val parts = m.parts.filter { it !is AiPart.Text || it.text.isNotEmpty() }
                        .filter { it !is AiPart.Image || (vision && m.role == AiRole.USER) }
                    val text = parts.filterIsInstance<AiPart.Text>().joinToString("\n") { it.text }
                    val images = parts.filterIsInstance<AiPart.Image>()
                    val roleName = if (m.role == AiRole.ASSISTANT) "assistant" else "user"
                    if (text.isEmpty() && images.isEmpty()) continue
                    val prefix = if (roleName == "user" && prepend != null) "$prepend\n\n" else ""
                    // Only a turn that is actually sent consumes the folded system prompt.
                    if (roleName == "user") prepend = null
                    addJsonObject {
                        put("role", roleName)
                        if (images.isEmpty()) {
                            put("content", prefix + text)
                        } else {
                            putJsonArray("content") {
                                if (prefix.isNotEmpty() || text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", prefix + text) }
                                for (img in images) addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:${img.mimeType};base64,${base64(img.bytes)}") }
                                }
                            }
                        }
                    }
                }
            }
            put("stream", true)
            if (sendsStreamOptions) putJsonObject("stream_options") { put("include_usage", true) }
            put(maxTokensParam(call), call.maxOutputTokens)
            if (call.variant and VARIANT_DROP_TEMPERATURE == 0 && call.temperature != null && supportsTemperature(call.model)) {
                put("temperature", clampTemperature(call.temperature))
            }
            if (call.variant and VARIANT_DROP_EFFORT == 0) reasoningEffort(call)?.let { put("reasoning_effort", it) }
        }
    }

    override fun chatRequest(call: ChatCall): Request =
        Request.Builder().url("${call.baseUrl}/chat/completions")
            .header("Authorization", "Bearer ${call.apiKey}")
            .header("Accept", "text/event-stream")
            .post(LenientJson.encodeToString(JsonObject.serializer(), chatBody(call)).encodeToByteArray().toRequestBody(JSON_MEDIA))
            .build()

    override fun variantAfterError(call: ChatCall, status: Int, body: String): ChatCall? {
        if (status != 400 && status != 422) return null
        val lower = ((ErrorMapper.providerMessage(body) ?: "") + " " + body.take(2000)).lowercase()
        val unsupported = listOf("unsupported", "not supported", "unrecognized", "unknown parameter", "not allowed", "does not support", "invalid parameter", "use '")
            .any { lower.contains(it) }
        if (!unsupported) return null
        val v = call.variant
        return when {
            (lower.contains("max_tokens") || lower.contains("max_completion_tokens")) && v and VARIANT_SWAP_MAX_TOKENS == 0 ->
                call.copy(variant = v or VARIANT_SWAP_MAX_TOKENS)
            lower.contains("temperature") && call.temperature != null && v and VARIANT_DROP_TEMPERATURE == 0 ->
                call.copy(variant = v or VARIANT_DROP_TEMPERATURE)
            lower.contains("reasoning_effort") && v and VARIANT_DROP_EFFORT == 0 && reasoningEffort(call) != null ->
                call.copy(variant = v or VARIANT_DROP_EFFORT)
            else -> null
        }
    }

    override fun newDecoder(): StreamDecoder = OpenAiStreamDecoder(this)

    companion object {
        const val VARIANT_SWAP_MAX_TOKENS = 1
        const val VARIANT_DROP_TEMPERATURE = 2
        const val VARIANT_DROP_EFFORT = 4
    }
}

/** Decodes Chat Completions chunks; `reasoning_content` is kept apart from the answer. */
class OpenAiStreamDecoder(private val adapter: ProviderAdapter) : StreamDecoder {
    private var refused = false

    override fun decode(event: SseEvent): List<StreamPart> {
        if (event.isDone) return listOf(StreamPart.End)
        val json = parseObject(event.data) ?: return emptyList()
        json["error"].obj()?.let { err ->
            return listOf(StreamPart.Failure(ErrorMapper.streamError(adapter.id, err["type"].str() ?: err["code"].str(), err["message"].str())))
        }
        val out = ArrayList<StreamPart>(2)
        val choice = json["choices"].arr()?.firstOrNull().obj()
        if (choice != null) {
            val delta = choice["delta"].obj() ?: choice["message"].obj()
            delta?.get("reasoning_content").str()?.takeIf { it.isNotEmpty() }?.let { out += StreamPart.Reasoning(it) }
            delta?.get("content").str()?.takeIf { it.isNotEmpty() }?.let { out += StreamPart.Text(it) }
            if (!refused && !delta?.get("refusal").str().isNullOrEmpty()) {
                refused = true
                out += StreamPart.Stop(StopReason.REFUSAL, "refusal")
            }
            choice["finish_reason"].str()?.let { r ->
                val reason = when (r) {
                    "stop" -> StopReason.END
                    "length" -> StopReason.MAX_TOKENS
                    "content_filter" -> StopReason.CONTENT_FILTER
                    else -> StopReason.OTHER
                }
                out += StreamPart.Stop(if (refused) StopReason.REFUSAL else reason, r)
            }
            choice["usage"].obj()?.let { out += usage(it) }
        }
        json["usage"].obj()?.let { out += usage(it) }
        return out
    }

    private fun usage(u: JsonObject) = StreamPart.Usage(u["prompt_tokens"].int() ?: u["input_tokens"].int(), u["completion_tokens"].int() ?: u["output_tokens"].int())
}

// ---------------------------------------------------------------------------------------------
// OpenAI

private val TV = setOf(AiCapability.TEXT, AiCapability.VISION)
private val TVR = setOf(AiCapability.TEXT, AiCapability.VISION, AiCapability.REASONING)
private val TR = setOf(AiCapability.TEXT, AiCapability.REASONING)
private val T = setOf(AiCapability.TEXT)

/** Model facts for OpenAI ids, by catalogue entry or id pattern. */
object OpenAiModels {
    private class Family(
        val pattern: Regex, val caps: Set<AiCapability>, val context: Int, val maxOut: Int,
        val inUsd: Double?, val outUsd: Double?,
    )

    private fun f(p: String, caps: Set<AiCapability>, ctx: Int, out: Int, i: Double?, o: Double?) = Family(Regex(p), caps, ctx, out, i, o)

    // Most specific first.
    private val families = listOf(
        f("^gpt-5(\\.\\d+)?-nano", TVR, 400_000, 128_000, 0.05, 0.40),
        f("^gpt-5(\\.\\d+)?-mini", TVR, 400_000, 128_000, 0.25, 2.0),
        f("^gpt-5(\\.\\d+)?-chat", TV, 128_000, 16_384, 1.25, 10.0),
        f("^gpt-5", TVR, 400_000, 128_000, 1.25, 10.0),
        f("^gpt-4\\.1-nano", TV, 1_047_576, 32_768, 0.10, 0.40),
        f("^gpt-4\\.1-mini", TV, 1_047_576, 32_768, 0.40, 1.60),
        f("^gpt-4\\.1", TV, 1_047_576, 32_768, 2.0, 8.0),
        f("^gpt-4o-mini", TV, 128_000, 16_384, 0.15, 0.60),
        f("^gpt-4o", TV, 128_000, 16_384, 2.5, 10.0),
        f("^chatgpt-4o", TV, 128_000, 16_384, 5.0, 15.0),
        f("^gpt-4-turbo", TV, 128_000, 4_096, 10.0, 30.0),
        f("^gpt-4", T, 8_192, 4_096, 30.0, 60.0),
        f("^gpt-3\\.5-turbo", T, 16_385, 4_096, 0.5, 1.5),
        f("^o4-mini", TVR, 200_000, 100_000, 1.10, 4.40),
        f("^o3-mini", TR, 200_000, 100_000, 1.10, 4.40),
        f("^o3", TVR, 200_000, 100_000, 2.0, 8.0),
        f("^o1-mini", TR, 128_000, 65_536, 1.10, 4.40),
        f("^o1", TVR, 200_000, 100_000, 15.0, 60.0),
        f("^o\\d", TR, 200_000, 100_000, null, null),
        f("^gpt-", T, 128_000, 16_384, null, null),
    )

    private val excluded = listOf(
        "tts", "dall-e", "image", "realtime", "audio", "moderation", "search", "computer-use", "davinci",
        "babbage", "instruct", "codex", "deep-research", "-pro", "sora",
    )

    val catalogue: List<ModelSpec> = listOf(
        "gpt-4.1", "gpt-5", "gpt-5-mini", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini", "o3", "o4-mini", "gpt-5-nano", "gpt-4.1-nano",
    ).map { derive(it) } + listOf(
        ModelSpec("whisper-1", "Whisper", setOf(AiCapability.AUDIO_TRANSCRIPTION), 0, 0, usdPerAudioMinute = 0.006),
        ModelSpec("gpt-4o-transcribe", "GPT-4o Transcribe", setOf(AiCapability.AUDIO_TRANSCRIPTION), 0, 0, usdPerAudioMinute = 0.006),
        ModelSpec("gpt-4o-mini-transcribe", "GPT-4o mini Transcribe", setOf(AiCapability.AUDIO_TRANSCRIPTION), 0, 0, usdPerAudioMinute = 0.003),
        ModelSpec("text-embedding-3-small", "Embedding 3 small", setOf(AiCapability.EMBEDDINGS), 8_191, 0, 0.02, null),
        ModelSpec("text-embedding-3-large", "Embedding 3 large", setOf(AiCapability.EMBEDDINGS), 8_191, 0, 0.13, null),
    )

    private val byId = catalogue.associateBy { it.id }

    fun spec(id: String): ModelSpec = byId[id] ?: derive(id)

    /** Reasoning family (o-series, GPT-5 except the chat variant). */
    fun isReasoning(id: String): Boolean = spec(id).has(AiCapability.REASONING)

    /** Takes `max_completion_tokens`: o-series and the whole GPT-5 family. */
    fun usesCompletionTokens(id: String): Boolean {
        val l = id.lowercase()
        return Regex("^o\\d").containsMatchIn(l) || l.startsWith("gpt-5")
    }

    fun displayName(id: String): String {
        if (!id.startsWith("gpt-")) return id
        val parts = id.removePrefix("gpt-").split('-')
        return "GPT-" + parts.first() + parts.drop(1).joinToString("") { " $it" }
    }

    private fun derive(id: String): ModelSpec {
        val l = id.lowercase()
        if (l.startsWith("whisper") || l.contains("transcribe")) {
            return ModelSpec(id, displayName(id), setOf(AiCapability.AUDIO_TRANSCRIPTION), 0, 0, usdPerAudioMinute = if (l.contains("mini")) 0.003 else 0.006)
        }
        if (l.startsWith("text-embedding")) {
            val price = when {
                l.contains("3-small") -> 0.02
                l.contains("3-large") -> 0.13
                l.contains("ada") -> 0.10
                else -> null
            }
            return ModelSpec(id, id, setOf(AiCapability.EMBEDDINGS), 8_191, 0, price, null)
        }
        if (excluded.any { l.contains(it) }) return ModelSpec(id, id, emptySet(), 0, 0)
        val fam = families.firstOrNull { it.pattern.containsMatchIn(l) }
            ?: return ModelSpec(id, id, emptySet(), 0, 0)
        return ModelSpec(id, displayName(id), fam.caps, fam.context, fam.maxOut, fam.inUsd, fam.outUsd)
    }

    private val snapshotSuffix = Regex("-(\\d{4}-\\d{2}-\\d{2}|\\d{4})$")

    /** Dated snapshots are hidden when their alias is available. */
    fun isSnapshotOfListed(id: String, all: Collection<String>): Boolean {
        val m = snapshotSuffix.find(id) ?: return false
        return id.substring(0, m.range.first) in all
    }
}

class OpenAiAdapter : OpenAiCompatibleAdapter() {
    override val id = AiProviderId.OPENAI
    override val defaultBaseUrl = "https://api.openai.com/v1"
    override val customBaseUrlExample = "https://your-proxy.example.com/v1"
    override val keyConsoleUrl = "https://platform.openai.com/api-keys"
    override val keyPrefixHint: String = "sk-"
    override val catalogue: List<ModelSpec> = OpenAiModels.catalogue

    override fun spec(modelId: String): ModelSpec = OpenAiModels.spec(modelId)

    override fun preferredIds(capability: AiCapability): List<String> = when (capability) {
        AiCapability.TEXT -> listOf("gpt-4.1", "gpt-5", "gpt-4o", "gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini")
        AiCapability.VISION -> listOf("gpt-4.1", "gpt-5", "gpt-4o", "gpt-5-mini", "gpt-4.1-mini", "gpt-4o-mini")
        AiCapability.AUDIO_TRANSCRIPTION -> listOf("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe")
        AiCapability.EMBEDDINGS -> listOf("text-embedding-3-small", "text-embedding-3-large")
        AiCapability.REASONING -> listOf("o4-mini", "o3", "gpt-5", "gpt-5-mini")
    }

    override fun isListed(spec: ModelSpec, all: Collection<String>): Boolean =
        spec.capabilities.isNotEmpty() && !OpenAiModels.isSnapshotOfListed(spec.id, all)

    override fun usesCompletionTokens(model: ModelSpec) = OpenAiModels.usesCompletionTokens(model.id)

    override fun systemRole(model: ModelSpec): String? {
        val l = model.id.lowercase()
        return when {
            l.startsWith("o1-mini") || l.startsWith("o1-preview") -> null
            model.has(AiCapability.REASONING) -> "developer"
            else -> "system"
        }
    }

    override fun reasoningEffort(call: ChatCall): String? =
        if (call.model.has(AiCapability.REASONING) && call.task.effort() == Effort.LOW && !call.model.id.startsWith("o1")) "low" else null

    // ---- Audio transcription (POST /audio/transcriptions)

    fun transcriptionRequest(baseUrl: String, apiKey: String, file: File, model: String, language: String?): Request {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", file.name, audioBody(file))
            .apply {
                if (TranscriptionParser.supportsSegments(model)) {
                    addFormDataPart("response_format", "verbose_json")
                    addFormDataPart("timestamp_granularities[]", "segment")
                } else {
                    addFormDataPart("response_format", "json")
                }
                TranscriptionParser.isoLanguage(language)?.let { addFormDataPart("language", it) }
            }
            .build()
        return Request.Builder().url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
    }

    // ---- Embeddings (POST /embeddings)

    fun embeddingsBody(model: String, inputs: List<String>): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("input") { inputs.forEach { add(it) } }
        put("encoding_format", "float")
    }

    fun embeddingsRequest(baseUrl: String, apiKey: String, model: String, inputs: List<String>): Request =
        Request.Builder().url("$baseUrl/embeddings")
            .header("Authorization", "Bearer $apiKey")
            .post(LenientJson.encodeToString(JsonObject.serializer(), embeddingsBody(model, inputs)).encodeToByteArray().toRequestBody(JSON_MEDIA))
            .build()

    /** Parses an embeddings response into vectors ordered by `index`, plus prompt tokens. */
    fun parseEmbeddings(body: String): Pair<List<FloatArray>, Int?> {
        val json = parseObject(body) ?: throw AiException("OpenAI returned an unreadable embeddings response.")
        val data = json["data"].arr() ?: throw AiException("OpenAI returned no embeddings.")
        val vectors = data.mapNotNull { it.obj() }
            .sortedBy { it["index"].int() ?: 0 }
            .map { o ->
                val v = o["embedding"].arr() ?: JsonArray(emptyList())
                FloatArray(v.size) { i -> (v[i] as JsonPrimitive).contentOrNull?.toFloatOrNull() ?: 0f }
            }
        return vectors to json["usage"].obj()?.get("prompt_tokens").int()
    }
}

private fun audioBody(file: File): RequestBody {
    val type = when (file.extension.lowercase()) {
        "m4a", "mp4", "aac" -> "audio/mp4"
        "mp3", "mpeg", "mpga" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
    return file.asRequestBody(type.toMediaType())
}

// ---------------------------------------------------------------------------------------------
// DeepSeek

class DeepSeekAdapter : OpenAiCompatibleAdapter() {
    override val id = AiProviderId.DEEPSEEK
    override val defaultBaseUrl = "https://api.deepseek.com"
    override val customBaseUrlExample = "https://api.deepseek.com"
    override val keyConsoleUrl = "https://platform.deepseek.com/api_keys"
    override val keyPrefixHint: String = "sk-"

    override val catalogue: List<ModelSpec> = listOf(
        ModelSpec("deepseek-chat", "DeepSeek Chat", T, 131_072, 8_192, 0.27, 1.10),
        ModelSpec("deepseek-reasoner", "DeepSeek Reasoner", TR, 131_072, 65_536, 0.55, 2.19),
    )
    private val byId = catalogue.associateBy { it.id }

    override fun spec(modelId: String): ModelSpec = byId[modelId] ?: ModelSpec(
        modelId, modelId,
        if (modelId.contains("reasoner") || modelId.contains("r1")) TR else T,
        131_072, 8_192,
    )

    override fun preferredIds(capability: AiCapability): List<String> = when (capability) {
        AiCapability.TEXT -> listOf("deepseek-chat", "deepseek-reasoner")
        AiCapability.REASONING -> listOf("deepseek-reasoner")
        else -> emptyList()
    }
}

// ---------------------------------------------------------------------------------------------
// Moonshot (Kimi)

object MoonshotModels {
    fun spec(id: String): ModelSpec {
        val l = id.lowercase()
        val vision = l.contains("vision") || l == "kimi-latest" || l.startsWith("kimi-latest") || l.contains("k1.5") || l.contains("k1-5")
        val caps = buildSet {
            add(AiCapability.TEXT)
            if (vision) add(AiCapability.VISION)
            if (l.contains("thinking") || l.contains("k1.5") || l.contains("k1-5")) add(AiCapability.REASONING)
        }
        val (ctx, price) = when {
            l.contains("moonshot-v1-8k") -> 8_192 to (0.2 to 2.0)
            l.contains("moonshot-v1-32k") -> 32_768 to (1.0 to 3.0)
            l.contains("moonshot-v1-128k") -> 131_072 to (2.0 to 5.0)
            l.startsWith("kimi-k2") -> (if (l.contains("0905") || l.contains("turbo") || l.contains("thinking")) 262_144 else 131_072) to (0.6 to 2.5)
            else -> 131_072 to (null to null)
        }
        val maxOut = when {
            ctx <= 8_192 -> 4_096
            else -> 16_384
        }
        return ModelSpec(id, displayName(id), caps, ctx, maxOut, price.first, price.second)
    }

    private fun displayName(id: String): String = when {
        id.startsWith("kimi-") -> "Kimi " + id.removePrefix("kimi-").replace('-', ' ')
        id.startsWith("moonshot-v1-") -> "Moonshot v1 " + id.removePrefix("moonshot-v1-").replace('-', ' ')
        else -> id
    }
}

class MoonshotAdapter : OpenAiCompatibleAdapter() {
    override val id = AiProviderId.MOONSHOT
    override val defaultBaseUrl = "https://api.moonshot.ai/v1"
    override val customBaseUrlExample = "https://api.moonshot.cn/v1"
    override val keyConsoleUrl = "https://platform.moonshot.ai/console/api-keys"
    override val keyPrefixHint: String = "sk-"
    override val sendsStreamOptions = false

    override val catalogue: List<ModelSpec> = listOf(
        "kimi-latest", "kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-32k", "moonshot-v1-128k", "moonshot-v1-8k",
        "moonshot-v1-8k-vision-preview", "moonshot-v1-32k-vision-preview", "moonshot-v1-128k-vision-preview",
    ).map { MoonshotModels.spec(it) }

    override fun spec(modelId: String): ModelSpec = MoonshotModels.spec(modelId)

    override fun preferredIds(capability: AiCapability): List<String> = when (capability) {
        AiCapability.TEXT -> listOf("kimi-latest", "kimi-k2-turbo-preview", "kimi-k2-0905-preview", "moonshot-v1-32k")
        AiCapability.VISION -> listOf("kimi-latest", "moonshot-v1-32k-vision-preview", "moonshot-v1-8k-vision-preview", "moonshot-v1-128k-vision-preview")
        else -> emptyList()
    }

    override fun clampTemperature(t: Float): Float = t.coerceIn(0f, 1f)
}

/** OpenAI transcription responses → segments with absolute timeline offsets. */
object TranscriptionParser {
    /** Only whisper-1 returns segment timestamps (verbose_json); the gpt-4o transcribe models return text. */
    fun supportsSegments(model: String): Boolean = model.lowercase().startsWith("whisper")

    /** "en-US" -> "en" (ISO-639-1 as the API expects); anything else (names, 3-letter codes) is a 400, so omit it and let the model detect. */
    fun isoLanguage(tag: String?): String? =
        tag?.trim()?.takeIf { it.isNotEmpty() }?.substringBefore('-')?.substringBefore('_')?.lowercase()
            ?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }

    data class Parsed(
        val segments: List<TranscriptSegment>,
        val language: String?,
        val text: String,
    )

    /**
     * Parses one file's response. [offsetMs] is the start of this file on the recording
     * timeline; [durationMs] its length (used when the response has no timestamps).
     */
    fun parse(body: String, offsetMs: Long, durationMs: Long): Parsed {
        val json = parseObject(body)
        if (json == null) {
            // response_format=text fallback (plain body).
            val t = body.trim()
            return Parsed(if (t.isEmpty()) emptyList() else listOf(TranscriptSegment(offsetMs, offsetMs + durationMs, t)), null, t)
        }
        val text = json["text"].str()?.trim().orEmpty()
        val language = json["language"].str()
        val segs = json["segments"].arr()
        val list = if (segs != null && segs.isNotEmpty()) {
            segs.mapNotNull { it.obj() }.mapNotNull { s ->
                val t = s["text"].str()?.trim().orEmpty()
                if (t.isEmpty()) return@mapNotNull null
                val start = (s["start"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: 0.0
                val end = (s["end"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: start
                val startMs = offsetMs + Math.round(start * 1000)
                val endMs = (offsetMs + Math.round(end * 1000)).coerceAtLeast(startMs)
                TranscriptSegment(startMs, if (durationMs > 0) endMs.coerceAtMost(offsetMs + durationMs).coerceAtLeast(startMs) else endMs, t)
            }
        } else if (text.isNotEmpty()) {
            val dur = durationMs.takeIf { it > 0 }
                ?: ((json["duration"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.let { Math.round(it * 1000) } ?: 0L)
            listOf(TranscriptSegment(offsetMs, offsetMs + dur, text))
        } else emptyList()
        return Parsed(list, language, text)
    }
}
