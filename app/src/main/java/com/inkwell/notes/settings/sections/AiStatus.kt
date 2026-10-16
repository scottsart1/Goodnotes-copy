package com.inkwell.notes.settings.sections

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiPrefs
import com.inkwell.notes.core.model.AiTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether an AI task can run right now, and with which model. */
internal data class AiTaskStatus(val available: Boolean, val modelName: String?)

/**
 * Checks off the main thread (key lookups touch the Keystore) whether [task] has a model.
 * Re-checks whenever the AI preferences change. Null while checking.
 */
@Composable
internal fun rememberAiTaskStatus(graph: AppGraph, task: AiTask, prefs: AiPrefs): State<AiTaskStatus?> =
    produceState<AiTaskStatus?>(initialValue = null, graph, task, prefs) {
        value = withContext(Dispatchers.IO) {
            try {
                if (!prefs.enabled || !graph.ai.isAvailable(task)) {
                    AiTaskStatus(false, null)
                } else {
                    val ref = graph.ai.modelFor(task)
                    AiTaskStatus(true, ref?.let { r -> graph.ai.modelInfo(r)?.displayName ?: r.modelId })
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w("Settings", "AI availability check failed: ${e.javaClass.simpleName}")
                AiTaskStatus(false, null)
            }
        }
    }
