package com.inkwell.notes.whiteboard.logic

/** Items anchored in [positionMs] - [windowMs] .. [positionMs] of [recordingId] are emphasised. */
data class BoardHighlight(val recordingId: String, val positionMs: Long, val windowMs: Long = BoardAnchors.WINDOW_MS)

/**
 * Decides what the board highlights for audio: the notes written at the playback position
 * while a recording of this board plays (throttled), and the notes revealed by "show notes
 * written here", which win for [holdMs] before playback takes over again (or the highlight
 * clears when nothing plays). Every method returns true when [current] changed and must be
 * pushed to the canvas. Single-threaded (main).
 */
class PlaybackHighlighter(
    private val throttleMs: Long = 150,
    val holdMs: Long = 6_000,
) {
    var current: BoardHighlight? = null; private set

    /** Latest playback target while playback is active (applied or throttled away). */
    private var playbackTarget: BoardHighlight? = null
    private var lastAppliedAt = Long.MIN_VALUE
    private var heldUntil = Long.MIN_VALUE

    /** True while [current] comes from a reveal rather than from playback. */
    val isHeld: Boolean get() = heldUntil != Long.MIN_VALUE

    /**
     * A playback update. [recordingId] is the playing recording when it belongs to this board
     * (null otherwise); [enabled] is the "highlight during playback" setting.
     */
    fun onPlayback(recordingId: String?, playing: Boolean, positionMs: Long, enabled: Boolean, nowMs: Long): Boolean {
        if (!enabled || !playing || recordingId == null) {
            playbackTarget = null
            lastAppliedAt = Long.MIN_VALUE
            if (isHeld || current == null) return false
            current = null
            return true
        }
        val next = BoardHighlight(recordingId, positionMs)
        playbackTarget = next
        if (isHeld) {
            if (nowMs < heldUntil) return false
            heldUntil = Long.MIN_VALUE
        } else if (current?.recordingId == recordingId && lastAppliedAt != Long.MIN_VALUE && nowMs >= lastAppliedAt && nowMs - lastAppliedAt < throttleMs) {
            return false
        }
        lastAppliedAt = nowMs
        if (next == current) return false
        current = next
        return true
    }

    /** "Show notes written here" revealed [highlight]: show it now and hold it for [holdMs]. */
    fun onReveal(highlight: BoardHighlight, nowMs: Long): Boolean {
        heldUntil = nowMs + holdMs
        if (highlight == current) return false
        current = highlight
        return true
    }

    /** Ends an expired reveal: playback's target takes over, or the highlight clears. Call after [holdMs]. */
    fun onTick(nowMs: Long): Boolean {
        if (!isHeld || nowMs < heldUntil) return false
        heldUntil = Long.MIN_VALUE
        val next = playbackTarget
        lastAppliedAt = if (next != null) nowMs else Long.MIN_VALUE
        if (next == current) return false
        current = next
        return true
    }

    /** Forgets everything (the screen is leaving). Returns true when something was highlighted. */
    fun clear(): Boolean {
        val had = current != null
        current = null
        playbackTarget = null
        heldUntil = Long.MIN_VALUE
        lastAppliedAt = Long.MIN_VALUE
        return had
    }
}
