package com.inkwell.notes.audio.ui.panel

import android.content.Intent
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.inkwell.notes.R
import com.inkwell.notes.ai.AiServiceImpl
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.PlaybackState
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.audio.logic.MarkdownBlocks
import com.inkwell.notes.audio.logic.TranscriptAction
import com.inkwell.notes.audio.logic.TranscriptFilter
import com.inkwell.notes.audio.logic.TranscriptPrompts
import com.inkwell.notes.audio.logic.Waveform
import com.inkwell.notes.audio.recorder.AudioFiles
import com.inkwell.notes.audio.transcription.Transcriptions
import com.inkwell.notes.core.model.AiEvent
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRequest
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AiUsage
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.DocKind
import com.inkwell.notes.core.model.ModelRef
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.core.storage.FileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

/** What the transcription and AI features can do right now (no key = both false). */
internal data class AiAvailability(
    val transcribe: Boolean = false,
    val summarize: Boolean = false,
    val transcriptionModel: ModelRef? = null,
    val transcriptionModelName: String? = null,
    val usdPerAudioMinute: Double? = null,
)

/** A confirmation before audio or text leaves the device. */
internal sealed interface Consent {
    val provider: AiProviderId

    data class Transcribe(val recording: Recording, override val provider: AiProviderId, val minutes: Int, val cost: String?) : Consent
    data class Ask(val pending: PendingAsk, override val provider: AiProviderId, val withNotes: Boolean) : Consent
}

internal data class PendingAsk(
    val action: TranscriptAction,
    val recording: Recording,
    val request: AiRequest,
    val provider: AiProviderId?,
    val estimatedTokens: Int,
    val estimatedCostUsd: Double?,
    val consented: Boolean = false,
    val sizeConfirmed: Boolean = false,
)

internal data class AiResultState(
    val action: TranscriptAction,
    val recording: Recording,
    val text: String = "",
    val running: Boolean = true,
    val error: String? = null,
    val estimatedTokens: Int = 0,
    val estimatedCostUsd: Double? = null,
    val usage: AiUsage? = null,
    val saving: Boolean = false,
    val savedTitle: String? = null,
    val saveError: String? = null,
)

internal class RecordingsViewModel(private val graph: AppGraph, val docId: String) : ViewModel() {
    private val context get() = graph.context
    private val db = graph.db

    // ------------------------------------------------------------------ data

    private val loadedFlag = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = loadedFlag

    val recordings: StateFlow<List<Recording>> = db.recordings().observeForDocument(docId)
        .map { rows -> rows.map { it.toModel() }.sortedByDescending { it.startedAt } }
        .flowOn(Dispatchers.IO)
        .onEach { loadedFlag.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recordingState: StateFlow<RecordingState> = graph.recording.state
    val playback: StateFlow<PlaybackState> = graph.playback.state

    val selectedId = MutableStateFlow<String?>(null)
    private var autoSelected = false

    val selected: StateFlow<Recording?> = combine(recordings, selectedId) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val transcript: StateFlow<List<TranscriptSegment>> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.transcripts().observe(id).map { rows -> rows.map { it.toModel() } } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val work: StateFlow<WorkInfo?> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(null) else Transcriptions.observe(context, id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val query = MutableStateFlow("")

    val matches: StateFlow<List<TranscriptFilter.Match>> = combine(transcript, query) { segs, q -> TranscriptFilter.filter(segs, q) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Envelope of the selected recording (0..1 per 100 ms), null while loading or missing. */
    val waveform: StateFlow<FloatArray?> = selected
        .map { r -> r?.let { Triple(it.id, it.waveformPath, it.durationMs) } }
        .distinctUntilChanged()
        .mapLatest { key ->
            val rec = selected.value ?: return@mapLatest null
            if (key == null) return@mapLatest null
            withContext(Dispatchers.IO) {
                AudioFiles.readWaveform(AudioFiles.waveform(graph.files, rec))
                    ?.let { Waveform.decode(Waveform.trim(it, rec.durationMs)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val availability: StateFlow<AiAvailability> = graph.settings.settings
        .map { withContext(Dispatchers.IO) { computeAvailability() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AiAvailability())

    // ------------------------------------------------------------------ UI state

    var message by mutableStateOf<String?>(null)
    var renamingId by mutableStateOf<String?>(null)
    var exportingId by mutableStateOf<String?>(null)
    var pendingDelete by mutableStateOf<Recording?>(null)
    var consent by mutableStateOf<Consent?>(null)
    var largeRequest by mutableStateOf<PendingAsk?>(null)
    var aiResult by mutableStateOf<AiResultState?>(null)
    var pickingPassage by mutableStateOf(false)
    private var aiJob: Job? = null

    init {
        // Open straight into the only recording, saving a tap.
        viewModelScope.launch {
            recordings.collect { list ->
                if (!autoSelected && loadedFlag.value) {
                    autoSelected = true
                    if (list.size == 1 && selectedId.value == null) selectedId.value = list.first().id
                }
                val id = selectedId.value
                if (id != null && list.none { it.id == id } && loadedFlag.value) selectedId.value = null
            }
        }
    }

    private fun computeAvailability(): AiAvailability {
        val ai = runCatching { graph.ai }.getOrNull() ?: return AiAvailability()
        val transcribe = runCatching { ai.isAvailable(AiTask.TRANSCRIPTION) }.getOrDefault(false)
        val summarize = runCatching { ai.isAvailable(AiTask.SUMMARIZE) }.getOrDefault(false)
        val model = if (transcribe) runCatching { ai.modelFor(AiTask.TRANSCRIPTION) }.getOrNull() else null
        val name = model?.let { runCatching { ai.modelInfo(it)?.displayName }.getOrNull() ?: it.modelId }
        // Speech-to-text is billed per audio minute; the price table lives in the AI engine.
        val perMinute = model?.let { ref -> runCatching { (ai as? AiServiceImpl)?.engine?.models?.spec(ref)?.usdPerAudioMinute }.getOrNull() }
        return AiAvailability(transcribe, summarize, model, name, perMinute)
    }

    // ------------------------------------------------------------------ navigation

    fun select(id: String?) {
        if (selectedId.value != id) {
            query.value = ""
            pickingPassage = false
        }
        renamingId = null
        selectedId.value = id
    }

    // ------------------------------------------------------------------ rename

    fun rename(rec: Recording, title: String) {
        renamingId = null
        val name = title.trim().replace('\n', ' ').take(MAX_TITLE)
        if (name.isEmpty() || name == rec.title) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { db.recordings().rename(rec.id, name) } }
                .onFailure { Log.w(TAG, "Rename failed", it) }
        }
    }

    // ------------------------------------------------------------------ playback

    fun isLoaded(rec: Recording): Boolean = playback.value.recording?.id == rec.id

    private fun ensureLoaded(rec: Recording) {
        val loaded = playback.value.recording
        if (loaded == null || loaded.id != rec.id || loaded.segmentFiles != rec.segmentFiles) graph.playback.load(rec)
    }

    fun togglePlay(rec: Recording) {
        val p = playback.value
        if (p.recording?.id == rec.id && p.playing) {
            graph.playback.pause()
        } else {
            ensureLoaded(rec)
            graph.playback.play()
        }
    }

    fun seek(rec: Recording, positionMs: Long, play: Boolean = false) {
        ensureLoaded(rec)
        graph.playback.seekTo(positionMs.coerceIn(0, rec.durationMs.coerceAtLeast(0)))
        if (play) graph.playback.play()
    }

    fun skip(rec: Recording, deltaMs: Long) {
        val pos = if (isLoaded(rec)) playback.value.positionMs else 0L
        seek(rec, pos + deltaMs)
    }

    fun setSpeed(speed: Float) = graph.playback.setSpeed(speed)

    fun positionFor(rec: Recording): Long = if (isLoaded(rec)) playback.value.positionMs else 0L

    // ------------------------------------------------------------------ transcription

    fun transcriptionMinutes(rec: Recording): Int = AudioText.minutesCeil(rec.durationMs)

    fun transcriptionCost(rec: Recording): String? {
        val perMinute = availability.value.usdPerAudioMinute ?: return null
        return AudioText.usd(perMinute * rec.durationMs.coerceAtLeast(0) / 60_000.0)
    }

    fun requestTranscription(rec: Recording) {
        val a = availability.value
        if (!a.transcribe) {
            message = context.getString(R.string.audio_transcribe_unavailable)
            return
        }
        val provider = a.transcriptionModel?.provider ?: AiProviderId.OPENAI
        if (graph.settings.current.privacy.confirmAiTransmission && !AudioAiConsent.has(provider)) {
            consent = Consent.Transcribe(rec, provider, transcriptionMinutes(rec), transcriptionCost(rec))
            return
        }
        startTranscription(rec)
    }

    private fun startTranscription(rec: Recording) {
        viewModelScope.launch {
            runCatching { Transcriptions.enqueue(graph, docId, rec.id, replace = true) }
                .onFailure {
                    Log.w(TAG, "Could not queue transcription", it)
                    message = context.getString(R.string.audio_transcribe_failed)
                }
        }
    }

    fun cancelTranscription(rec: Recording) {
        viewModelScope.launch { runCatching { Transcriptions.cancel(graph, rec.id) } }
    }

    fun acceptConsent(dontAskAgain: Boolean) {
        val c = consent ?: return
        consent = null
        AudioAiConsent.grant(c.provider)
        if (dontAskAgain) {
            graph.appScope.launch {
                runCatching { graph.settings.update { it.copy(privacy = it.privacy.copy(confirmAiTransmission = false)) } }
            }
        }
        when (c) {
            is Consent.Transcribe -> startTranscription(c.recording)
            is Consent.Ask -> proceed(c.pending.copy(consented = true))
        }
    }

    fun dismissConsent() {
        consent = null
    }

    // ------------------------------------------------------------------ AI quick actions

    fun runAction(action: TranscriptAction, passageIndex: Int? = null) {
        if (action.needsPassage && passageIndex == null) {
            pickingPassage = true
            return
        }
        pickingPassage = false
        val rec = selected.value ?: return
        val segments = transcript.value
        if (segments.isEmpty()) return
        viewModelScope.launch {
            val notes = if (action.needsNotes) loadNotesText() else null
            if (action.needsNotes && notes.isNullOrBlank()) {
                aiResult = AiResultState(action, rec, running = false, error = context.getString(R.string.audio_ai_no_notes))
                return@launch
            }
            val ai = graph.ai
            val model = runCatching { ai.modelFor(AiTask.SUMMARIZE) }.getOrNull()
            val contextTokens = model?.let { runCatching { ai.modelInfo(it)?.contextTokens }.getOrNull() }
            val passage = passageIndex?.let { TranscriptPrompts.passageAround(segments, it) }
            val request = withContext(Dispatchers.Default) {
                TranscriptPrompts.request(action, rec.title, segments, docId, contextTokens, passage, notes)
            }
            val tokens = runCatching { ai.estimateTokens(request) }.getOrDefault(0)
            val cost = runCatching { ai.estimateCostUsd(request, 900) }.getOrNull()
            proceed(PendingAsk(action, rec, request, model?.provider, tokens, cost))
        }
    }

    private suspend fun loadNotesText(): String = withContext(Dispatchers.IO) {
        runCatching { graph.search.documentText(docId) }.getOrDefault(emptyList())
            .filter { it.source == TextSource.HANDWRITING || it.source == TextSource.TYPED }
            .joinToString("\n") { p -> p.pageIndex?.let { "[p.${it + 1}] ${p.text.trim()}" } ?: p.text.trim() }
    }

    private fun proceed(p: PendingAsk) {
        val provider = p.provider
        if (!p.consented && provider != null && graph.settings.current.privacy.confirmAiTransmission && !AudioAiConsent.has(provider)) {
            consent = Consent.Ask(p, provider, withNotes = p.action.needsNotes)
            return
        }
        val limit = graph.settings.current.ai.confirmAboveTokens
        if (!p.sizeConfirmed && limit > 0 && p.estimatedTokens > limit) {
            largeRequest = p
            return
        }
        startAsk(p)
    }

    fun confirmLarge() {
        val p = largeRequest ?: return
        largeRequest = null
        proceed(p.copy(sizeConfirmed = true))
    }

    fun dismissLarge() {
        largeRequest = null
    }

    private fun startAsk(p: PendingAsk) {
        aiJob?.cancel()
        aiResult = AiResultState(p.action, p.recording, estimatedTokens = p.estimatedTokens, estimatedCostUsd = p.estimatedCostUsd)
        aiJob = viewModelScope.launch {
            try {
                graph.ai.stream(p.request).collect { e ->
                    val cur = aiResult ?: return@collect
                    aiResult = when (e) {
                        is AiEvent.Delta -> cur.copy(text = cur.text + e.text)
                        is AiEvent.Done -> cur.copy(text = e.text.ifBlank { cur.text }, running = false, usage = e.usage)
                        is AiEvent.Error -> cur.copy(running = false, error = e.message.ifBlank { context.getString(R.string.audio_ai_failed) })
                    }
                }
                aiResult?.let { if (it.running) aiResult = it.copy(running = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Quick action failed", t)
                aiResult = aiResult?.copy(running = false, error = t.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.audio_ai_failed))
            }
        }
    }

    fun stopAi() {
        aiJob?.cancel()
        aiResult = aiResult?.copy(running = false)
    }

    fun dismissAi() {
        aiJob?.cancel()
        aiJob = null
        aiResult = null
    }

    /** Saves the answer as a new typed document (never touches the notebook itself). */
    fun saveAiAsTyped(actionLabel: String) {
        val r = aiResult ?: return
        if (r.text.isBlank() || r.saving || r.savedTitle != null) return
        aiResult = r.copy(saving = true, saveError = null)
        viewModelScope.launch {
            try {
                val docTitle = runCatching { graph.library.getDocument(docId)?.title }.getOrNull().orEmpty()
                val doc = graph.library.createDocument(DocKind.TYPED)
                val id = { UUID.randomUUID().toString() }
                val blocks = buildList {
                    add(TypedBlock(id(), BlockType.HEADING, "$actionLabel · ${r.recording.title}", level = 1))
                    add(TypedBlock(id(), BlockType.PARAGRAPH, context.getString(R.string.audio_ai_typed_source, r.recording.title, docTitle)))
                    addAll(MarkdownBlocks.parse(r.text, id))
                }
                val json = TYPED_JSON.encodeToString(TypedDocument.serializer(), TypedDocument(blocks = blocks))
                withContext(Dispatchers.IO) {
                    FileStore.writeAtomically(graph.files.typedFile(doc.id)) { it.write(json.toByteArray()) }
                }
                runCatching { graph.library.touch(doc.id) }
                runCatching { graph.indexing.documentMetaChanged(doc.id) }
                runCatching { graph.sync.onDocumentChanged(doc.id) }
                aiResult = aiResult?.copy(saving = false, savedTitle = doc.title)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Saving the answer failed", t)
                aiResult = aiResult?.copy(saving = false, saveError = context.getString(R.string.audio_ai_save_failed))
            }
        }
    }

    // ------------------------------------------------------------------ export / delete

    fun export(rec: Recording, onShare: (Intent) -> Unit) {
        if (exportingId != null) return
        exportingId = rec.id
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { graph.export.exportRecording(rec.id) }
                val share = graph.export.shareIntent(file, AUDIO_MIME)
                onShare(Intent.createChooser(share, context.getString(R.string.audio_share_title)))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Export failed", t)
                message = context.getString(R.string.audio_export_failed)
            } finally {
                exportingId = null
            }
        }
    }

    fun requestDelete(rec: Recording) {
        val s = recordingState.value
        if (s is RecordingState.Active && s.recordingId == rec.id) {
            message = context.getString(R.string.audio_delete_while_recording)
            return
        }
        pendingDelete = rec
    }

    fun confirmDelete() {
        val rec = pendingDelete ?: return
        pendingDelete = null
        if (selectedId.value == rec.id) select(null)
        if (playback.value.recording?.id == rec.id) graph.playback.release()
        // Outlives the panel: deleting must finish even if it is closed right away.
        graph.appScope.launch(Dispatchers.IO) {
            try {
                runCatching { Transcriptions.cancel(graph, rec.id) }
                db.transcripts().deleteForRecording(rec.id)
                db.recordings().delete(rec.id)
                graph.files.audioDir(rec.documentId, rec.id).deleteRecursively()
                runCatching { graph.search.replaceTranscript(docId, rec.id, emptyList(), "deleted") }
            } catch (t: Throwable) {
                Log.w(TAG, "Delete failed", t)
                withContext(Dispatchers.Main) { message = context.getString(R.string.audio_delete_failed) }
            }
        }
    }

    override fun onCleared() {
        aiJob?.cancel()
    }

    companion object {
        private const val TAG = "RecordingsPanel"
        private const val MAX_TITLE = 120
        const val AUDIO_MIME = "audio/mp4"
        private val TYPED_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
