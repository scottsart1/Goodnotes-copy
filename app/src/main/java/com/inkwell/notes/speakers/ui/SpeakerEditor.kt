package com.inkwell.notes.speakers.ui

import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.speakers.SpeakerService
import com.inkwell.notes.speakers.ui.logic.BlockMove
import com.inkwell.notes.speakers.ui.logic.NamingOp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speaker changes made from the UI. They run on the app scope (a change finishes even when
 * the panel closes right away), off the main thread, and report failures through [onError]
 * on the main thread. Only exception types are logged, never names.
 */
internal class SpeakerEditor(private val graph: AppGraph, private val onError: (Throwable) -> Unit) {
    private val service: SpeakerService get() = graph.speakers

    fun run(
        onDone: () -> Unit = {},
        onFailure: (Throwable) -> Unit = onError,
        block: suspend SpeakerService.() -> Unit,
    ): Job = graph.appScope.launch(Dispatchers.Main.immediate) {
        try {
            withContext(Dispatchers.IO) { service.block() }
            onDone()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.w(TAG, "Speaker change failed: ${t.javaClass.simpleName}")
            onFailure(t)
        }
    }

    fun name(recordingId: String, label: String, name: String, isMe: Boolean = false) = run { nameSpeaker(recordingId, label, name, isMe) }

    fun assign(recordingId: String, label: String, personId: String?) = run { assignSpeaker(recordingId, label, personId) }

    fun confirm(recordingId: String, label: String) = run { confirmSpeaker(recordingId, label) }

    fun merge(recordingId: String, fromLabel: String, intoLabel: String) = run { mergeSpeakers(recordingId, fromLabel, intoLabel) }

    /** "Change speaker" on the block starting at [startMs]. */
    fun move(recordingId: String, startMs: Long, move: BlockMove) = run {
        when (move) {
            is BlockMove.ToVoice -> reassignSegment(recordingId, startMs, move.label)
            is BlockMove.ToPerson -> {
                reassignSegment(recordingId, startMs, move.newLabel)
                assignSpeaker(recordingId, move.newLabel, move.personId)
            }
            is BlockMove.ToNewName -> {
                reassignSegment(recordingId, startMs, move.newLabel)
                nameSpeaker(recordingId, move.newLabel, move.name)
            }
        }
    }

    /** Applies the naming sheet's changes in order; stops at the first failure. */
    fun apply(recordingId: String, ops: List<NamingOp>, onDone: () -> Unit, onFailure: (Throwable) -> Unit) = run(onDone, onFailure) {
        for (op in ops) {
            when (op) {
                is NamingOp.Assign -> assignSpeaker(recordingId, op.label, op.personId)
                is NamingOp.Name -> nameSpeaker(recordingId, op.label, op.name, op.isMe)
                is NamingOp.Confirm -> confirmSpeaker(recordingId, op.label)
                is NamingOp.SetMe -> setMe(op.personId)
                NamingOp.Dismiss -> dismissNaming(recordingId)
            }
        }
    }

    private companion object {
        const val TAG = "Speakers"
    }
}
