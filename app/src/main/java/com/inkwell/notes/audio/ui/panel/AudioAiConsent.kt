package com.inkwell.notes.audio.ui.panel

import com.inkwell.notes.core.model.AiProviderId
import java.util.Collections

/** Providers the user already agreed to send recording content to during this process. */
internal object AudioAiConsent {
    private val acknowledged: MutableSet<AiProviderId> = Collections.synchronizedSet(mutableSetOf())

    fun has(provider: AiProviderId): Boolean = provider in acknowledged

    fun grant(provider: AiProviderId) {
        acknowledged += provider
    }
}
