package com.inkwell.notes.speakers.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf

/**
 * Small UI memory for the speaker screens. Nothing here is about a voice: one flag on disk
 * (the explainer was seen) and, for the process only, prompts the user closed.
 */
internal object SpeakerUiMemory {
    private const val FILE = "inkwell_speakers_ui"
    private const val KEY_EXPLAINED = "naming_explained"

    /** Recordings whose dock prompt was dismissed in this process (snapshot state: main thread). */
    val dismissedPrompts = mutableStateListOf<String>()

    fun explained(context: Context): Boolean =
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_EXPLAINED, false) }.getOrDefault(false)

    fun markExplained(context: Context) {
        runCatching { context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_EXPLAINED, true).apply() }
    }
}
