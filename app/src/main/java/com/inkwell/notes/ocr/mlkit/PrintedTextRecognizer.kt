package com.inkwell.notes.ocr.mlkit

import android.graphics.Bitmap
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.inkwell.notes.core.model.RecognitionResult
import com.inkwell.notes.core.model.RecognizedBlock
import com.inkwell.notes.ocr.ink.InkGeometry
import com.inkwell.notes.ocr.text.OcrScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Printed-text OCR with the bundled ML Kit Latin model (works offline). Images are downscaled
 * so the longest edge is at most [OcrScaling.MAX_EDGE] px; text blocks keep their boxes,
 * mapped to page points when the page size is known (otherwise to the input bitmap's pixels).
 */
internal class PrintedTextRecognizer {
    private val client: TextRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Throws when ML Kit fails, so background callers can retry later. */
    suspend fun recognize(bitmap: Bitmap, pageWidthPt: Float?, pageHeightPt: Float?): RecognitionResult = withContext(Dispatchers.Default) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return@withContext empty()
        val owned = ArrayList<Bitmap>(2)
        try {
            var src = bitmap
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && src.config == Bitmap.Config.HARDWARE) {
                src = src.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext empty()
                owned += src
            }
            val (w, h) = OcrScaling.scaledSize(src.width, src.height)
            val input = if (w != src.width || h != src.height) {
                Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) owned += it }
            } else src

            val text = client.process(InputImage.fromBitmap(input, 0)).await()

            val toW: Float
            val toH: Float
            if (pageWidthPt != null && pageHeightPt != null && pageWidthPt > 0f && pageHeightPt > 0f) {
                toW = pageWidthPt; toH = pageHeightPt
            } else {
                toW = bitmap.width.toFloat(); toH = bitmap.height.toFloat()
            }
            val blocks = ArrayList<RecognizedBlock>()
            for (block in text.textBlocks) {
                val lines = block.lines
                val content = lines.joinToString("\n") { it.text.trim() }.trim().ifEmpty { block.text.trim() }
                if (content.isEmpty()) continue
                val box = block.boundingBox ?: continue
                val bounds = OcrScaling.mapBox(
                    box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat(),
                    input.width, input.height, toW, toH,
                )
                val confidences = lines.mapNotNull { l -> l.confidence.takeIf { it > 0f && it.isFinite() } }
                val conf = if (confidences.isEmpty()) null else confidences.average().toFloat().coerceIn(0f, 1f)
                blocks += RecognizedBlock(content, bounds, conf)
            }
            RecognitionResult(
                text = blocks.joinToString("\n") { it.text },
                blocks = blocks,
                confidence = InkGeometry.meanConfidence(blocks.map { it.text to it.confidence }),
                engine = ENGINE,
            )
        } finally {
            owned.forEach { if (it !== bitmap && !it.isRecycled) it.recycle() }
        }
    }

    private fun empty() = RecognitionResult("", emptyList(), null, ENGINE)

    companion object {
        const val ENGINE = "mlkit-text"
    }
}
