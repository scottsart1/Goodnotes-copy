package com.inkwell.notes.editor.logic

/** What the Record control does with the result of a microphone permission prompt. */
sealed interface MicRequestOutcome {
    /** The prompt never reached the user (another prompt was showing, the activity was recreated): do nothing. */
    data object Cancelled : MicRequestOutcome

    /** The microphone may be used (notifications may still have been declined): start recording. */
    data object Granted : MicRequestOutcome

    /** The microphone was refused; [permanently] when the system will no longer show the prompt. */
    data class Denied(val permanently: Boolean) : MicRequestOutcome
}

/**
 * Pure decisions of the microphone permission flow shared by the notebook editor and the
 * whiteboard (the system calls themselves stay in the composables). Unit tested.
 */
object MicPermissions {
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * The permissions one Record tap asks for: the microphone, plus notifications on Android
     * 13+ when they are missing, so the recording notification with Pause and Stop can show.
     */
    fun toRequest(sdkInt: Int, hasNotifications: Boolean): List<String> = buildList {
        add(RECORD_AUDIO)
        if (sdkInt >= 33 && !hasNotifications) add(POST_NOTIFICATIONS)
    }

    /**
     * @param result the system's answer (permission -> granted); empty when the request was cancelled.
     * @param micGrantedNow whether RECORD_AUDIO is held right now (the result may omit it).
     * @param canShowRationale `shouldShowRequestPermissionRationale(RECORD_AUDIO)` after the result.
     */
    fun outcome(result: Map<String, Boolean>, micGrantedNow: Boolean, canShowRationale: Boolean): MicRequestOutcome {
        if (result.isEmpty()) return if (micGrantedNow) MicRequestOutcome.Granted else MicRequestOutcome.Cancelled
        if (result[RECORD_AUDIO] == true || micGrantedNow) return MicRequestOutcome.Granted
        return MicRequestOutcome.Denied(permanently = !canShowRationale)
    }
}
