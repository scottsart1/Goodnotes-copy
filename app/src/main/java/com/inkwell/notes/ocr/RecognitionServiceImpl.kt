package com.inkwell.notes.ocr

import android.graphics.Bitmap
import android.util.Log
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.RecognitionResult
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ocr.ink.InkGeometry
import com.inkwell.notes.ocr.mlkit.HandwritingModels
import com.inkwell.notes.ocr.mlkit.HandwritingRecognizer
import com.inkwell.notes.ocr.mlkit.InkRecognizerPool
import com.inkwell.notes.ocr.mlkit.NetworkState
import com.inkwell.notes.ocr.mlkit.PrintedTextRecognizer
import com.inkwell.notes.ocr.text.AiTranscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device recognition: ML Kit digital ink for handwriting (models downloaded per language on
 * first need), the bundled ML Kit Latin model for printed text, and, only when the user set it
 * up, a vision model through [com.inkwell.notes.ai.AiService] that marks what it cannot read.
 */
class RecognitionServiceImpl(private val graph: AppGraph) : RecognitionService {
    internal val models: HandwritingModels by lazy {
        HandwritingModels(graph.appScope) { _ ->
            // Pages skipped while the model was missing can be indexed now.
            (graph.indexing as? IndexingSchedulerImpl)?.catchUpSoon()
        }
    }
    private val pool by lazy { InkRecognizerPool(graph.appScope) }
    private val handwriting by lazy { HandwritingRecognizer(pool) }
    private val printed by lazy { PrintedTextRecognizer() }

    /** Outcome of handwriting recognition for background indexing. */
    internal sealed interface InkOutcome {
        data class Recognized(val result: RecognitionResult) : InkOutcome
        /** The language model is not on the device (and was not downloaded now). */
        data object ModelMissing : InkOutcome
        /** No handwriting model exists for the language. */
        data object Unsupported : InkOutcome
    }

    internal fun languageTag(requested: String? = null): String =
        models.normalize(requested ?: graph.settings.current.ocr.handwritingLanguages.firstOrNull())

    override suspend fun recognizeStrokes(strokes: List<Stroke>, languageTag: String?): RecognitionResult? {
        val tag = languageTag(languageTag)
        val usable = InkGeometry.writingStrokes(strokes)
        if (usable.isEmpty()) return RecognitionResult("", emptyList(), null, HandwritingRecognizer.ENGINE)
        return try {
            var model = models.readyModel(tag)
            if (model == null && models.isSupported(tag) && NetworkState.isOnline(graph.context)) {
                // First use: fetch the model now and wait a little; if it takes longer the
                // download continues and the status flow reports DOWNLOADING.
                val job = models.startDownload(tag)
                val ok = job?.let { withTimeoutOrNull(INTERACTIVE_DOWNLOAD_WAIT_MS) { it.await() } } == true
                if (ok) model = models.model(tag)
            }
            if (model == null) null else handwriting.recognize(usable, tag, model)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Handwriting recognition failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Handwriting recognition for indexing. Downloads a missing model only when
     * [allowDownload] (the caller checks the network policy). ML Kit errors are thrown.
     */
    internal suspend fun recognizeForIndex(strokes: List<Stroke>, tag: String, allowDownload: Boolean): InkOutcome {
        if (!models.isSupported(tag)) return InkOutcome.Unsupported
        var model = models.readyModel(tag)
        if (model == null && allowDownload && models.download(tag)) model = models.model(tag)
        model ?: return InkOutcome.ModelMissing
        return InkOutcome.Recognized(handwriting.recognize(strokes, tag, model))
    }

    override suspend fun recognizeBitmap(bitmap: Bitmap, pageWidthPt: Float?, pageHeightPt: Float?): RecognitionResult = try {
        printed.recognize(bitmap, pageWidthPt, pageHeightPt)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w(TAG, "Text recognition failed: ${e.javaClass.simpleName}")
        RecognitionResult("", emptyList(), null, PrintedTextRecognizer.ENGINE)
    }

    /** Like [recognizeBitmap] but throws on failure, so background work can retry. */
    internal suspend fun recognizeBitmapOrThrow(bitmap: Bitmap, pageWidthPt: Float?, pageHeightPt: Float?): RecognitionResult =
        printed.recognize(bitmap, pageWidthPt, pageHeightPt)

    override suspend fun recognizeWithAi(bitmap: Bitmap, hint: String?): RecognitionResult? {
        if (!graph.ai.isAvailable(AiTask.OCR_ASSIST)) return null
        val result = try {
            graph.ai.visionTranscribe(bitmap, hint)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Log.w(TAG, "Vision transcription failed: ${e.javaClass.simpleName}")
            null
        } ?: return null
        val parsed = AiTranscription.parse(result.text)
        val engine = "$AI_ENGINE_PREFIX${result.model}"
        // The model reports no positions: every paragraph covers the whole image.
        val whole = floatArrayOf(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        val blocks = parsed.paragraphs.map { RecognizedBlock(it, whole.copyOf(), null) }
        return RecognitionResult(parsed.text, blocks, null, engine, parsed.uncertain)
    }

    override fun handwritingModelStatus(languageTag: String): StateFlow<ModelStatus> = models.status(languageTag)

    override suspend fun downloadHandwritingModel(languageTag: String): Boolean = try {
        models.download(languageTag)
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        Log.w(TAG, "Model download failed: ${e.javaClass.simpleName}")
        false
    }

    companion object {
        private const val TAG = "Recognition"
        const val AI_ENGINE_PREFIX = "ai:"
        private const val INTERACTIVE_DOWNLOAD_WAIT_MS = 15_000L
    }
}
