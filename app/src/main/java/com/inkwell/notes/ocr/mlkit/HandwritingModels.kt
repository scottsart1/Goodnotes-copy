package com.inkwell.notes.ocr.mlkit

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.inkwell.notes.ocr.ModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Digital-ink recognition models (one per BCP-47 language tag): availability, download state
 * as a flow per language, and downloads that keep going even if the screen that asked leaves.
 */
internal class HandwritingModels(
    private val scope: CoroutineScope,
    /** Called (on a background thread) after a model finished downloading. */
    private val onReady: (String) -> Unit,
) {
    private val statuses = ConcurrentHashMap<String, MutableStateFlow<ModelStatus>>()
    private val downloads = HashMap<String, Deferred<Boolean>>()
    private val manager: RemoteModelManager by lazy { RemoteModelManager.getInstance() }

    fun normalize(tag: String?): String = tag?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TAG

    private fun identifier(tag: String): DigitalInkRecognitionModelIdentifier? = try {
        DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag)
    } catch (e: Exception) {
        null
    }

    fun model(tag: String): DigitalInkRecognitionModel? = identifier(normalize(tag))?.let { DigitalInkRecognitionModel.builder(it).build() }

    fun isSupported(tag: String): Boolean = identifier(normalize(tag)) != null

    /** Status flow for [tag]; the first call checks the device in the background. */
    fun status(tag: String): StateFlow<ModelStatus> = flowFor(normalize(tag))

    private fun flowFor(key: String): MutableStateFlow<ModelStatus> {
        statuses[key]?.let { return it }
        val supported = identifier(key) != null
        val flow = MutableStateFlow(if (supported) ModelStatus.NOT_DOWNLOADED else ModelStatus.UNAVAILABLE)
        val previous = statuses.putIfAbsent(key, flow)
        if (previous != null) return previous
        if (supported) scope.launch { runCatching { refresh(key) } }
        return flow
    }

    private fun downloading(key: String): Boolean = synchronized(downloads) { downloads[key]?.isActive == true }

    /** Re-checks whether the model is on the device and publishes the result. */
    suspend fun refresh(tag: String): ModelStatus {
        val key = normalize(tag)
        val flow = flowFor(key)
        val model = model(key) ?: return ModelStatus.UNAVAILABLE.also { flow.value = it }
        if (downloading(key)) return ModelStatus.DOWNLOADING.also { flow.value = it }
        val downloaded: Boolean? = try {
            manager.isModelDownloaded(model).await()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Model check failed: ${e.javaClass.simpleName}")
            null
        }
        if (downloading(key)) return ModelStatus.DOWNLOADING.also { flow.value = it }
        when (downloaded) {
            true -> flow.value = ModelStatus.READY
            false -> flow.value = ModelStatus.NOT_DOWNLOADED
            null -> Unit
        }
        return flow.value
    }

    /** The model when it is on the device, otherwise null (never downloads). */
    suspend fun readyModel(tag: String): DigitalInkRecognitionModel? =
        if (refresh(tag) == ModelStatus.READY) model(tag) else null

    /**
     * Starts the download of [tag]'s model, or joins one in progress. The download runs in the
     * application scope, so it completes even when the caller is cancelled. Null when the
     * language has no model.
     */
    fun startDownload(tag: String): Deferred<Boolean>? {
        val key = normalize(tag)
        val model = model(key) ?: return null
        val flow = flowFor(key)
        synchronized(downloads) {
            downloads[key]?.takeIf { it.isActive }?.let { return it }
            flow.value = ModelStatus.DOWNLOADING
            val job = scope.async(Dispatchers.IO) {
                try {
                    manager.download(model, DownloadConditions.Builder().build()).await()
                    val ok = try { manager.isModelDownloaded(model).await() } catch (e: Exception) { true }
                    flow.value = if (ok) ModelStatus.READY else ModelStatus.NOT_DOWNLOADED
                    if (ok) runCatching { onReady(key) }
                    ok
                } catch (c: CancellationException) {
                    flow.value = ModelStatus.NOT_DOWNLOADED
                    throw c
                } catch (e: Exception) {
                    Log.w(TAG, "Handwriting model download failed: ${e.javaClass.simpleName}")
                    flow.value = ModelStatus.NOT_DOWNLOADED
                    false
                }
            }
            downloads[key] = job
            return job
        }
    }

    /** Downloads (or waits for) [tag]'s model. True when it is ready afterwards. */
    suspend fun download(tag: String): Boolean {
        val job = startDownload(tag) ?: return false
        return try {
            job.await()
        } catch (c: CancellationException) {
            if (job.isCancelled) false else throw c
        }
    }

    companion object {
        private const val TAG = "HandwritingModels"
        const val DEFAULT_TAG = "en-US"
    }
}
