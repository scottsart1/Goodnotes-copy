package com.inkwell.notes.speakers.logic

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** A pinned model file: fetched from an immutable revision URL and checked by size and SHA-256. */
data class ModelSpec(
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val description: String,
    val license: String,
)

/** The on-device speaker models (about 32.5 MB together). */
object SpeakerModelSpecs {
    /** Recorded with every voiceprint: a different embedding model invalidates stored prints. */
    const val EMBEDDING_MODEL_ID = "wespeaker_resnet34_lm"
    const val ENGINE_ID = "device:$EMBEDDING_MODEL_ID"

    val SEGMENTATION = ModelSpec(
        fileName = "segmentation.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/9403a6902bb58e3d5ae8c7e77c3422de279db2e0/model.onnx",
        sizeBytes = 5_992_913L,
        sha256 = "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079",
        description = "pyannote segmentation 3.0",
        license = "MIT",
    )

    val EMBEDDING = ModelSpec(
        fileName = "embedding.onnx",
        url = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/wespeaker_en_voxceleb_resnet34_LM.onnx",
        sizeBytes = 26_530_550L,
        sha256 = "e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012",
        description = "WeSpeaker ResNet34-LM (VoxCeleb)",
        license = "Apache-2.0",
    )

    val ALL = listOf(SEGMENTATION, EMBEDDING)

    val TOTAL_BYTES: Long = ALL.sumOf { it.sizeBytes }
}

/** Integrity checks for downloaded model files. Pure (java.io only). */
object ModelFiles {
    sealed interface Outcome {
        data object Ok : Outcome
        data class SizeMismatch(val actual: Long) : Outcome
        data object HashMismatch : Outcome
        data class Unreadable(val message: String?) : Outcome
    }

    /** The finished file of [spec] in [dir]. */
    fun target(dir: File, spec: ModelSpec): File = File(dir, spec.fileName)

    /** The in-progress download of [spec] in [dir]. */
    fun part(dir: File, spec: ModelSpec): File = File(dir, spec.fileName + ".part")

    /** True when every model is on disk with the expected size (the hash was checked on download). */
    fun present(dir: File, specs: List<ModelSpec> = SpeakerModelSpecs.ALL): Boolean =
        specs.all { target(dir, it).let { f -> f.isFile && f.length() == it.sizeBytes } }

    /** Bytes of [specs] already on disk (finished files and partial downloads). */
    fun bytesOnDisk(dir: File, specs: List<ModelSpec> = SpeakerModelSpecs.ALL): Long = specs.sumOf { s ->
        val t = target(dir, s)
        if (t.isFile && t.length() == s.sizeBytes) s.sizeBytes else part(dir, s).let { p -> if (p.isFile) p.length().coerceAtMost(s.sizeBytes) else 0L }
    }

    /** Checks [file] against [spec] by size, then SHA-256. */
    fun verify(file: File, spec: ModelSpec): Outcome {
        if (!file.isFile) return Outcome.Unreadable("missing")
        val len = file.length()
        if (len != spec.sizeBytes) return Outcome.SizeMismatch(len)
        return try {
            val hex = sha256(file)
            if (hex.equals(spec.sha256, ignoreCase = true)) Outcome.Ok else Outcome.HashMismatch
        } catch (e: IOException) {
            Outcome.Unreadable(e.message)
        }
    }

    /**
     * Verifies a completed download and moves it into place atomically. On any mismatch the
     * partial file is deleted (a corrupt download must start over) and the outcome returned.
     */
    fun promote(part: File, target: File, spec: ModelSpec): Outcome {
        val outcome = verify(part, spec)
        if (outcome != Outcome.Ok) {
            part.delete()
            return outcome
        }
        if (target.exists() && !target.delete()) return Outcome.Unreadable("cannot replace ${target.name}")
        if (!part.renameTo(target)) {
            part.delete()
            return Outcome.Unreadable("rename failed")
        }
        return Outcome.Ok
    }

    /**
     * Finishes a download whose size and SHA-256 were measured while streaming: moves it into
     * place when both match [spec], else deletes the partial file.
     */
    fun finish(part: File, target: File, spec: ModelSpec, streamedBytes: Long, streamedSha256: String): Outcome {
        val outcome = when {
            streamedBytes != spec.sizeBytes || part.length() != spec.sizeBytes -> Outcome.SizeMismatch(part.length())
            !streamedSha256.equals(spec.sha256, ignoreCase = true) -> Outcome.HashMismatch
            else -> Outcome.Ok
        }
        if (outcome != Outcome.Ok) {
            part.delete()
            return outcome
        }
        if (target.exists() && !target.delete()) return Outcome.Unreadable("cannot replace ${target.name}")
        if (!part.renameTo(target)) {
            part.delete()
            return Outcome.Unreadable("rename failed")
        }
        return Outcome.Ok
    }

    /** SHA-256 of the first [length] bytes of [file] fed into [md] (to resume a streamed hash). */
    fun digestPrefix(file: File, length: Long, md: MessageDigest) {
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("File shorter than expected")
                md.update(buf, 0, n)
                left -= n
            }
        }
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /**
     * Where to resume a download of [spec] whose partial file holds [partBytes]: the Range
     * start, or 0 to start over (nothing yet, or more bytes than the file can have).
     */
    fun resumeFrom(partBytes: Long, spec: ModelSpec): Long = if (partBytes in 1 until spec.sizeBytes) partBytes else 0L

    private const val HEX = "0123456789abcdef"
}
