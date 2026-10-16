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
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.core.model.SpeakerPrefs
import com.inkwell.notes.core.model.SpeakerTurn
import com.inkwell.notes.core.model.TextSource
import com.inkwell.notes.core.model.TranscriptSegment
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.speakers.DiarizationPhase
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.ui.SpeakerEditor
import com.inkwell.notes.speakers.ui.logic.BlockMove
import com.inkwell.notes.speakers.ui.logic.NameSuggestions
import com.inkwell.notes.speakers.ui.logic.SpeakerTranscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
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
    /** The "Use AI features" master switch. */
    val aiEnabled: Boolean = true,
    val transcribe: Boolean = false,
    val summarize: Boolean = false,
    val transcriptionModel: ModelRef? = null,
    val transcriptionModelName: String? = null,
    val usdPerAudioMinute: Double? = null,
    /** Speakers are labelled by OpenAI while transcribing (engine OPENAI and an OpenAI key). */
    val cloudSpeakers: Boolean = false,
)

/** A confirmation before audio or text leaves the device. */
internal sealed interface Consent {
    val provider: AiProviderId

    /** [voiceClips]: short reference clips of known people are sent with the audio. */
    data class Transcribe(
        val recording: Recording,
        override val provider: AiProviderId,
        val minutes: Int,
        val cost: String?,
        val voiceClips: Boolean = false,
    ) : Consent
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

/** A speaker dialog of the panel. */
internal sealed interface SpeakerDialog {
    /** Name or rename one voice. */
    data class Name(val speaker: RecordingSpeaker) : SpeakerDialog
    /** Merge one voice into another. */
    data class Merge(val speaker: RecordingSpeaker) : SpeakerDialog
    /** "Change speaker" for one transcript block. */
    data class Block(val recordingId: String, val segment: TranscriptSegment) : SpeakerDialog
}

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

    // ------------------------------------------------------------------ speakers

    val speakerPrefs: StateFlow<SpeakerPrefs> = graph.settings.settings.map { it.speakers }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, graph.settings.current.speakers)

    /** Voices of the selected recording, in order of first appearance. */
    val speakers: StateFlow<List<RecordingSpeaker>> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else graph.speakers.speakers(id).catch { emit(emptyList()) } }
        .map { SpeakerTranscript.inOrder(it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val turns: StateFlow<List<SpeakerTurn>> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else graph.speakers.turns(id).catch { emit(emptyList()) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val diarization: StateFlow<DiarizationPhase> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(DiarizationPhase.None) else graph.speakers.phase(id).catch { emit(DiarizationPhase.None) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiarizationPhase.None)

    val needsNaming: StateFlow<Boolean> = selectedId
        .flatMapLatest { id -> if (id == null) flowOf(false) else graph.speakers.needsNaming(id).catch { emit(false) } }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val people: StateFlow<List<Person>> = graph.speakers.people()
        .catch { emit(emptyList()) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val modelState: StateFlow<SpeakerModelState> = graph.speakers.modelState

    /** The voice the transcript is filtered to (a label), or null for everyone. */
    val speakerFilter = MutableStateFlow<String?>(null)

    /** Why the transcription job could not label speakers in the cloud, when it says so. */
    val speakerNote: StateFlow<String?> = work
        .map { info -> info?.takeIf { it.state.isFinished }?.outputData?.getString(KEY_SPEAKER_NOTE)?.trim()?.takeIf { it.isNotEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Speakers and voice filter the transcript uses: none while the engine is off (stored names
     * still show, and a filter set earlier can't hide blocks without a way to clear it).
     */
    private val shownSpeakers: Flow<Pair<List<RecordingSpeaker>, String?>> =
        combine(speakers, speakerFilter, speakerPrefs) { s, f, p -> if (p.engine == SpeakerEngine.OFF) emptyList<RecordingSpeaker>() to null else s to f }

    val rows: StateFlow<List<SpeakerTranscript.Row>> = combine(transcript, query, shownSpeakers) { segs, q, (spk, only) ->
        SpeakerTranscript.rows(segs, q, spk.associateBy { it.label }, only)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Whole-percent share of talk time per label. */
    val shares: StateFlow<Map<String, Int>> = combine(speakers, turns) { s, t -> SpeakerTranscript.talkShares(s, t) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val speakerEditor = SpeakerEditor(graph) { message = context.getString(R.string.spk_action_failed) }

    private val namingFlows = HashMap<String, StateFlow<Boolean>>()

    /** Whether [recordingId] has voices waiting for a name (for the list's badge). Main thread. */
    fun needsNamingOf(recordingId: String): StateFlow<Boolean> = namingFlows.getOrPut(recordingId) {
        graph.speakers.needsNaming(recordingId)
            .catch { emit(false) }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    }

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
    /** The recording whose naming sheet is open. */
    var namingFor by mutableStateOf<String?>(null)
    var speakerDialog by mutableStateOf<SpeakerDialog?>(null)
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
        // A merged or removed voice can't stay the filter.
        viewModelScope.launch {
            speakers.collect { list ->
                val only = speakerFilter.value
                if (only != null && list.none { it.label == only }) speakerFilter.value = null
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
        val cloudSpeakers = transcribe && graph.settings.current.speakers.engine == SpeakerEngine.OPENAI &&
            runCatching { ai.canDiarize() }.getOrDefault(false)
        return AiAvailability(graph.settings.current.ai.enabled, transcribe, summarize, model, name, perMinute, cloudSpeakers)
    }

    // ------------------------------------------------------------------ navigation

    fun select(id: String?) {
        if (selectedId.value != id) {
            query.value = ""
            pickingPassage = false
            speakerFilter.value = null
            speakerDialog = null
        }
        renamingId = null
        selectedId.value = id
    }

    // ------------------------------------------------------------------ speakers

    fun toggleSpeakerFilter(label: String) {
        speakerFilter.value = if (speakerFilter.value == label) null else label
    }

    /** Seeks to the next (or previous) block of the filtered voice and plays from there. */
    fun seekToVoice(rec: Recording, forward: Boolean) {
        val key = speakerFilter.value ?: return
        val starts = SpeakerTranscript.blockStarts(key, transcript.value, speakers.value.associateBy { it.label }, turns.value)
        if (starts.isEmpty()) {
            val name = speakers.value.firstOrNull { it.label == key }?.displayName ?: key
            message = context.getString(R.string.spk_no_blocks, name)
            return
        }
        val pos = positionFor(rec)
        val target = if (forward) SpeakerTranscript.next(starts, pos) else SpeakerTranscript.previous(starts, pos)
        if (target != null) seek(rec, target, play = true)
    }

    fun identifySpeakers(rec: Recording, replace: Boolean) {
        speakerEditor.run(onFailure = { message = context.getString(R.string.spk_identify_start_failed) }) {
            identify(docId, rec.id, replace)
        }
    }

    fun cancelIdentify(rec: Recording) {
        speakerEditor.run { cancel(rec.id) }
    }

    fun nameVoice(s: RecordingSpeaker, name: String) {
        speakerDialog = null
        val clean = NameSuggestions.clean(name)
        if (clean.isNotEmpty()) speakerEditor.name(s.recordingId, s.label, clean)
    }

    fun assignVoice(s: RecordingSpeaker, person: Person?) {
        speakerDialog = null
        speakerEditor.assign(s.recordingId, s.label, person?.id)
    }

    fun confirmVoice(s: RecordingSpeaker) {
        speakerEditor.confirm(s.recordingId, s.label)
    }

    fun mergeVoice(from: RecordingSpeaker, into: RecordingSpeaker) {
        speakerDialog = null
        if (speakerFilter.value == from.label) speakerFilter.value = into.label
        speakerEditor.merge(from.recordingId, from.label, into.label)
    }

    fun moveBlock(recordingId: String, segment: TranscriptSegment, move: BlockMove?) {
        speakerDialog = null
        if (move == null || (move is BlockMove.ToVoice && move.label == segment.speakerLabel)) return
        speakerEditor.move(recordingId, segment.startMs, move)
    }

    fun changeSpeaker(rec: Recording, segment: TranscriptSegment) {
        speakerDialog = SpeakerDialog.Block(rec.id, segment)
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
            consent = Consent.Transcribe(rec, provider, transcriptionMinutes(rec), transcriptionCost(rec), voiceClips = a.cloudSpeakers)
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
            // Routing reads key presence and the model cache: keep it off the main thread.
            val pending = withContext(Dispatchers.IO) {
                val ai = graph.ai
                val model = runCatching { ai.modelFor(AiTask.SUMMARIZE) }.getOrNull()
                val contextTokens = model?.let { runCatching { ai.modelInfo(it)?.contextTokens }.getOrNull() }
                val passage = passageIndex?.let { TranscriptPrompts.passageAround(segments, it) }
                val request = TranscriptPrompts.request(action, rec.title, segments, docId, contextTokens, passage, notes)
                val tokens = runCatching { ai.estimateTokens(request) }.getOrDefault(0)
                val cost = runCatching { ai.estimateCostUsd(request, 900) }.getOrNull()
                PendingAsk(action, rec, request, model?.provider, tokens, cost)
            }
            proceed(pending)
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
                graph.ai.stream(p.request).flowOn(Dispatchers.IO).collect { e ->
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
                val title = withContext(Dispatchers.IO) {
                    val docTitle = runCatching { graph.library.getDocument(docId)?.title }.getOrNull().orEmpty()
                    val doc = graph.library.createDocument(DocKind.TYPED)
                    val id = { UUID.randomUUID().toString() }
                    val blocks = buildList {
                        add(TypedBlock(id(), BlockType.HEADING, "$actionLabel · ${r.recording.title}", level = 1))
                        add(TypedBlock(id(), BlockType.PARAGRAPH, context.getString(R.string.audio_ai_typed_source, r.recording.title, docTitle)))
                        addAll(MarkdownBlocks.parse(r.text, id))
                    }
                    val json = TYPED_JSON.encodeToString(TypedDocument.serializer(), TypedDocument(blocks = blocks))
                    FileStore.writeAtomically(graph.files.typedFile(doc.id)) { it.write(json.toByteArray()) }
                    runCatching { graph.library.touch(doc.id) }
                    runCatching { graph.indexing.documentMetaChanged(doc.id) }
                    runCatching { graph.sync.onDocumentChanged(doc.id) }
                    doc.title
                }
                aiResult = aiResult?.copy(saving = false, savedTitle = title)
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
        if (namingFor == rec.id) namingFor = null
        if (playback.value.recording?.id == rec.id) graph.playback.release()
        // Outlives the panel: deleting must finish even if it is closed right away.
        graph.appScope.launch(Dispatchers.IO) {
            try {
                runCatching { Transcriptions.cancel(graph, rec.id) }
                runCatching { graph.speakers.cancel(rec.id) }
                db.transcripts().deleteForRecording(rec.id)
                db.recordings().delete(rec.id)
                graph.files.audioDir(rec.documentId, rec.id).deleteRecursively()
                // The recording's speakers and turns go with its row; this drops its voice data.
                runCatching { graph.speakers.onRecordingDeleted(rec.id) }
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
        /** Output of the transcription job when OpenAI could not label speakers (see TranscriptionWorker). */
        const val KEY_SPEAKER_NOTE = "speakerNote"
        private val TYPED_JSON = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
