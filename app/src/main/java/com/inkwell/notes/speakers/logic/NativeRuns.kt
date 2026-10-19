package com.inkwell.notes.speakers.logic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Recordings whose analysis is inside native code (sherpa-onnx / onnxruntime). A native fault
 * kills the whole process with no Java exception to catch, and WorkManager then runs the job
 * again on every start: without this record one bad recording would crash the app in a loop.
 * The marker is written (fsynced) before the native pass and cleared after it, so a marker
 * found at the next start means that run died inside native code.
 */
@Serializable
data class NativeRuns(val entries: Map<String, Entry> = emptyMap()) {
    @Serializable
    data class Entry(
        /** A run of this recording entered native code and has not come back yet. */
        val inFlight: Boolean = false,
        /** Runs of this recording that died inside native code, in a row. */
        val crashes: Int = 0,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(r: NativeRuns): String = json.encodeToString(serializer(), r)

        fun decode(text: String): NativeRuns = runCatching { json.decodeFromString(serializer(), text) }.getOrDefault(NativeRuns())
    }
}

/** Pure rules over [NativeRuns]. */
object NativeRunRules {
    /** After this many runs of one recording died inside native code, stop retrying it. */
    const val MAX_CRASHES = 2

    sealed interface Decision {
        val next: NativeRuns

        /** Enter native code; [next] marks the run in flight. */
        data class Proceed(override val next: NativeRuns) : Decision

        /** Do not: earlier runs of this recording kept killing the app. */
        data class Stop(override val next: NativeRuns) : Decision
    }

    /** A run of [recordingId] is about to enter native code. */
    fun start(state: NativeRuns, recordingId: String): Decision {
        val e = state.entries[recordingId] ?: NativeRuns.Entry()
        val crashes = if (e.inFlight) e.crashes + 1 else e.crashes
        return if (crashes >= MAX_CRASHES) {
            Decision.Stop(state.with(recordingId, NativeRuns.Entry(inFlight = false, crashes = crashes)))
        } else {
            Decision.Proceed(state.with(recordingId, NativeRuns.Entry(inFlight = true, crashes = crashes)))
        }
    }

    /**
     * The run came back from native code: [completed] (every window analysed) forgets the
     * recording; otherwise (a Java exception, a stop) only the in-flight mark goes.
     */
    fun finish(state: NativeRuns, recordingId: String, completed: Boolean): NativeRuns {
        val e = state.entries[recordingId] ?: return state
        return if (completed) state.without(recordingId) else state.with(recordingId, e.copy(inFlight = false))
    }

    /** The user asked to try again (or the recording is gone): start from a clean slate. */
    fun reset(state: NativeRuns, recordingId: String): NativeRuns = state.without(recordingId)

    private fun NativeRuns.with(id: String, e: NativeRuns.Entry) = copy(entries = entries + (id to e))

    private fun NativeRuns.without(id: String) = if (id in entries) copy(entries = entries - id) else this
}
