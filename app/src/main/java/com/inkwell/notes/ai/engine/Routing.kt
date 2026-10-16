package com.inkwell.notes.ai.engine

import com.inkwell.notes.core.model.AiCapability
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiProviderSettings
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.ModelRef

/** Settings for [p], or defaults when an older settings file predates the provider. */
fun AiPrefs.providerSettings(p: AiProviderId): AiProviderSettings = providers.firstOrNull { it.provider == p } ?: AiProviderSettings(p)

/** Returns a copy with [p]'s settings transformed (appending them when absent). */
fun AiPrefs.updateProvider(p: AiProviderId, transform: (AiProviderSettings) -> AiProviderSettings): AiPrefs {
    val existing = providers.any { it.provider == p }
    val list = if (existing) providers.map { if (it.provider == p) transform(it) else it } else providers + transform(AiProviderSettings(p))
    return copy(providers = list)
}

/** Providers in settings order, then any missing ones in enum order. */
fun AiPrefs.providerOrder(): List<AiProviderId> = (providers.map { it.provider } + AiProviderId.entries).distinct()

/**
 * Picks the model for a task:
 * 1. the task's route, if its provider is usable and the model has the required capability;
 * 2. the global default model, if usable and capable;
 * 3. the first usable provider's default model with the capability.
 */
object ModelRouter {
    fun route(
        task: AiTask,
        prefs: AiPrefs,
        usable: (AiProviderId) -> Boolean,
        capabilities: (ModelRef) -> Set<AiCapability>,
        providerDefault: (AiProviderId, AiCapability) -> String?,
    ): ModelRef? {
        if (!prefs.enabled) return null
        val need = task.required
        fun ok(ref: ModelRef) = usable(ref.provider) && need in capabilities(ref)
        prefs.routing[task]?.let { if (ok(it)) return it }
        prefs.defaultModel?.let { if (ok(it)) return it }
        for (p in prefs.providerOrder()) {
            if (!usable(p)) continue
            prefs.providerSettings(p).defaultModel?.takeIf { it.isNotBlank() }?.let { id ->
                val ref = ModelRef(p, id)
                if (need in capabilities(ref)) return ref
            }
            providerDefault(p, need)?.let { id ->
                val ref = ModelRef(p, id)
                if (need in capabilities(ref)) return ref
            }
        }
        return null
    }
}
