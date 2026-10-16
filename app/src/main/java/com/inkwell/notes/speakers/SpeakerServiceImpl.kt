package com.inkwell.notes.speakers

import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.core.model.RecordingSpeaker
import com.inkwell.notes.core.model.SpeakerTurn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.UUID

/** SCAFFOLD STUB — replaced in place by the speaker identification implementation. */
class SpeakerServiceImpl(private val graph: AppGraph) : SpeakerService {
    override val modelState: StateFlow<SpeakerModelState> = MutableStateFlow(SpeakerModelState.NotDownloaded)
    override suspend fun downloadModels() = Unit
    override suspend fun deleteModels() = Unit
    override fun people(): Flow<List<Person>> = flowOf(emptyList())
    override suspend fun person(id: String): Person? = null
    override suspend fun createPerson(name: String, isMe: Boolean): Person =
        Person(UUID.randomUUID().toString(), name, isMe, 0, System.currentTimeMillis(), System.currentTimeMillis(), 0)
    override suspend fun renamePerson(personId: String, name: String) = Unit
    override suspend fun setMe(personId: String?) = Unit
    override suspend fun mergePeople(fromId: String, intoId: String) = Unit
    override suspend fun deletePerson(personId: String) = Unit
    override suspend fun deleteAllVoiceprints() = Unit
    override suspend fun enroll(personId: String, audio: File): Boolean = false
    override fun speakers(recordingId: String): Flow<List<RecordingSpeaker>> = flowOf(emptyList())
    override fun turns(recordingId: String): Flow<List<SpeakerTurn>> = flowOf(emptyList())
    override fun phase(recordingId: String): Flow<DiarizationPhase> = flowOf(DiarizationPhase.None)
    override suspend fun identify(docId: String, recordingId: String, replace: Boolean) = Unit
    override suspend fun cancel(recordingId: String) = Unit
    override suspend fun nameSpeaker(recordingId: String, label: String, name: String, isMe: Boolean): Person = createPerson(name, isMe)
    override suspend fun assignSpeaker(recordingId: String, label: String, personId: String?) = Unit
    override suspend fun confirmSpeaker(recordingId: String, label: String) = Unit
    override suspend fun reassignSegment(recordingId: String, startMs: Long, label: String) = Unit
    override suspend fun mergeSpeakers(recordingId: String, fromLabel: String, intoLabel: String) = Unit
    override fun needsNaming(recordingId: String): Flow<Boolean> = flowOf(false)
    override suspend fun dismissNaming(recordingId: String) = Unit
    override suspend fun onRecordingFinished(docId: String, recordingId: String) = Unit
    override suspend fun onTranscriptReady(docId: String, recordingId: String) = Unit
    override suspend fun saveDiarization(docId: String, result: DiarizationResult) = Unit
    override suspend fun referencesFor(docId: String, recordingId: String, max: Int): List<SpeakerReference> = emptyList()
    override suspend fun onRecordingDeleted(recordingId: String) = Unit
}
