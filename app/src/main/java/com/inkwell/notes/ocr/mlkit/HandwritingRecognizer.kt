package com.inkwell.notes.ocr.mlkit

import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import com.inkwell.notes.core.model.InkPoints
import com.inkwell.notes.core.model.RecognitionResult
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.core.model.Stroke
import com.inkwell.notes.ocr.ink.InkGeometry
import com.inkwell.notes.ocr.ink.InkLine
import com.inkwell.notes.ocr.ink.InkLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max
import com.google.mlkit.vision.digitalink.RecognitionResult as InkResult

/**
 * Keeps one ML Kit recogniser per language while recognition is in use and closes them after
 * a quiet period, so the model's memory is released when nobody is writing.
 */
internal class InkRecognizerPool(private val scope: CoroutineScope) {
    private val lock = Mutex()
    private val clients = HashMap<String, DigitalInkRecognizer>()
    private var idleJob: Job? = null

    suspend fun recognize(tag: String, model: DigitalInkRecognitionModel, ink: Ink, context: RecognitionContext): InkResult =
        lock.withLock {
            idleJob?.cancel()
            try {
                val client = clients.getOrPut(tag) {
                    DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).setMaxResultCount(MAX_CANDIDATES).build(),
                    )
                }
                client.recognize(ink, context).await()
            } finally {
                idleJob = scope.launch {
                    delay(IDLE_CLOSE_MS)
                    lock.withLock {
                        clients.values.forEach { runCatching { it.close() } }
                        clients.clear()
                    }
                }
            }
        }

    companion object {
        const val MAX_CANDIDATES = 5
        const val IDLE_CLOSE_MS = 90_000L
    }
}

/**
 * Handwriting to text, line by line: strokes are clustered into lines and words
 * ([InkLines]), each line is recognised with the previous text as pre-context and its own
 * writing area, and lines are joined with line breaks. Results of unchanged lines are reused,
 * so re-indexing a page after a small edit only recognises what changed.
 */
internal class HandwritingRecognizer(private val pool: InkRecognizerPool) {
    private data class LineResult(val text: String, val confidence: Float?)

    private val cache = object : LinkedHashMap<String, LineResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LineResult>?): Boolean = size > CACHE_SIZE
    }

    suspend fun recognize(strokes: List<Stroke>, tag: String, model: DigitalInkRecognitionModel): RecognitionResult =
        withContext(Dispatchers.Default) {
            val usable = InkGeometry.writingStrokes(strokes)
            val boxes = usable.mapIndexedNotNull { i, s -> InkGeometry.box(i, s) }
            val heights = boxes.associate { it.index to it.height }
            val lines = InkLines.cluster(boxes)
            val blocks = ArrayList<RecognizedBlock>(lines.size)
            var previous = ""
            for (line in lines) {
                ensureActive()
                val lineStrokes = line.order.map { usable[it] }
                val pre = previous.takeLast(PRE_CONTEXT_CHARS)
                val key = "$tag|$pre|${InkGeometry.signature(lineStrokes)}"
                val cached = synchronized(cache) { cache[key] }
                val r = cached ?: recognizeLine(lineStrokes, line, line.order.mapNotNull { heights[it] }, tag, model, pre)
                    .also { res -> synchronized(cache) { cache[key] = res } }
                val text = r.text.trim()
                if (text.isEmpty()) continue
                blocks += RecognizedBlock(text, floatArrayOf(line.left, line.top, line.right, line.bottom), r.confidence)
                previous = if (previous.isEmpty()) text else "$previous $text"
                if (previous.length > PRE_CONTEXT_CHARS * 4) previous = previous.takeLast(PRE_CONTEXT_CHARS * 2)
            }
            RecognitionResult(
                text = blocks.joinToString("\n") { it.text },
                blocks = blocks,
                confidence = InkGeometry.meanConfidence(blocks.map { it.text to it.confidence }),
                engine = ENGINE,
            )
        }

    private suspend fun recognizeLine(
        strokes: List<Stroke>,
        line: InkLine,
        heights: List<Float>,
        tag: String,
        model: DigitalInkRecognitionModel,
        pre: String,
    ): LineResult {
        val builder = Ink.builder()
        var last = Long.MIN_VALUE
        var added = 0
        for (s in strokes) {
            val times = InkGeometry.timestamps(s, last)
            val sb = Ink.Stroke.builder()
            var points = 0
            for (i in 0 until s.pointCount) {
                val x = s.points[i * InkPoints.STRIDE + InkPoints.X]
                val y = s.points[i * InkPoints.STRIDE + InkPoints.Y]
                if (!x.isFinite() || !y.isFinite()) continue
                sb.addPoint(Ink.Point.create(x, y, times[i]))
                points++
            }
            if (points == 0) continue
            builder.addStroke(sb.build())
            added++
            last = times.last()
        }
        if (added == 0) return LineResult("", null)
        val median = heights.sorted().let { if (it.isEmpty()) line.height else it[it.size / 2] }
        val area = WritingArea(max(line.width, 1f), max(max(line.height, median * WRITING_AREA_LETTERS), 1f))
        val context = RecognitionContext.builder().setPreContext(pre).setWritingArea(area).build()
        val result = pool.recognize(tag, model, builder.build(), context)
        val candidates = result.candidates
        val top = candidates.firstOrNull() ?: return LineResult("", null)
        return LineResult(top.text.orEmpty(), InkGeometry.confidenceFromScores(candidates.map { it.score }))
    }

    companion object {
        const val ENGINE = "mlkit-ink"
        /** ML Kit uses up to 20 characters of pre-context. */
        const val PRE_CONTEXT_CHARS = 20
        /** Writing area height in median letter heights, so lowercase reads as lowercase. */
        const val WRITING_AREA_LETTERS = 2.2f
        private const val CACHE_SIZE = 600
    }
}
