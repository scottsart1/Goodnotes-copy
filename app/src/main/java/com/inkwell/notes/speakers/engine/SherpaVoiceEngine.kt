package com.inkwell.notes.speakers.engine

import android.os.Process
import com.inkwell.notes.speakers.logic.RawSegment
import com.inkwell.notes.speakers.logic.SpeakerModelSpecs
import com.inkwell.notes.speakers.logic.VectorMath
import com.inkwell.notes.speakers.logic.VoiceEngine
import com.inkwell.notes.speakers.logic.VoiceScores
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * [VoiceEngine] over sherpa-onnx: pyannote segmentation 3.0 + WeSpeaker ResNet34-LM
 * embeddings, CPU only. The native library loads with these classes, so nothing outside the
 * device pipeline touches them (JVM tests use fakes). Not thread-safe: create, use and close
 * it on [newDispatcher]'s single thread.
 */
internal class SherpaVoiceEngine(
    private val segmentationModel: File,
    private val embeddingModel: File,
    private val threads: Int = THREADS,
) : VoiceEngine {
    override val modelId: String = SpeakerModelSpecs.EMBEDDING_MODEL_ID

    private var diarizer: OfflineSpeakerDiarization? = null
    private var extractor: SpeakerEmbeddingExtractor? = null

    private fun diarizer(): OfflineSpeakerDiarization = diarizer ?: OfflineSpeakerDiarization(
        assetManager = null,
        config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel.absolutePath),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            embedding = SpeakerEmbeddingExtractorConfig(
                model = embeddingModel.absolutePath,
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            clustering = FastClusteringConfig(numClusters = -1, threshold = VoiceScores.WINDOW_CLUSTER_THRESHOLD),
            minDurationOn = 0.3f,
            minDurationOff = 0.5f,
        ),
    ).also { diarizer = it }

    private fun extractor(): SpeakerEmbeddingExtractor = extractor ?: SpeakerEmbeddingExtractor(
        null,
        SpeakerEmbeddingExtractorConfig(model = embeddingModel.absolutePath, numThreads = 2, debug = false, provider = "cpu"),
    ).also { extractor = it }

    override fun diarize(samples: FloatArray, progress: (Float) -> Unit): List<RawSegment> {
        val d = diarizer()
        val segments = d.processWithCallback(
            samples,
            { done: Int, total: Int, _: Long ->
                if (total > 0) progress(done.toFloat() / total)
                0
            },
            0L,
        )
        return segments.map { RawSegment(it.start, it.end, it.speaker) }
    }

    override fun embed(samples: FloatArray): FloatArray? {
        if (samples.isEmpty()) return null
        val x = extractor()
        val stream = x.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            stream.inputFinished()
            if (!x.isReady(stream)) null else VectorMath.normalize(x.compute(stream)).takeIf { VectorMath.norm(it) > 0f }
        } finally {
            stream.release()
        }
    }

    override fun close() {
        runCatching { diarizer?.release() }
        runCatching { extractor?.release() }
        diarizer = null
        extractor = null
    }

    companion object {
        const val THREADS = 4
        private const val SAMPLE_RATE = 16_000

        /**
         * A single background-priority thread for the models. onnxruntime's worker threads are
         * created from it and inherit its priority, so analysis never competes with ink.
         */
        fun newDispatcher(): ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "InkwellVoices")
        }.asCoroutineDispatcher()
    }
}
