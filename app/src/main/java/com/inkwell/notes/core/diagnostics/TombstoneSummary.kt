package com.inkwell.notes.core.diagnostics

/**
 * The useful part of an Android native crash record (the tombstone protobuf that
 * ApplicationExitInfo returns for a native crash since Android 12): the signal, the abort
 * message, the stated causes and the crashing thread's backtrace. Memory dumps, registers and
 * log buffers are skipped on purpose: they can hold fragments of whatever was in memory. Pure.
 */
data class TombstoneSummary(
    val signal: String?,
    val abortMessage: String?,
    val causes: List<String>,
    val threadName: String?,
    val frames: List<String>,
) {
    /** Plain text for the crash report. */
    fun render(maxFrames: Int = 24): String = buildString {
        signal?.let { append("Signal: ").append(it).append('\n') }
        abortMessage?.takeIf { it.isNotBlank() }?.let { append("Abort: ").append(it.trim()).append('\n') }
        causes.forEach { append("Cause: ").append(it).append('\n') }
        threadName?.let { append("Thread: ").append(it).append('\n') }
        frames.take(maxFrames).forEachIndexed { i, f -> append("  #").append(i.toString().padStart(2, '0')).append(' ').append(f).append('\n') }
        if (frames.size > maxFrames) append("  … ").append(frames.size - maxFrames).append(" more frames\n")
    }.trimEnd()

    companion object {
        // Field numbers from AOSP system/core/debuggerd/proto/tombstone.proto.
        private const val T_TID = 6
        private const val T_SIGNAL = 10
        private const val T_ABORT = 14
        private const val T_CAUSES = 15
        private const val T_THREADS = 16
        private const val SIG_NUMBER = 1
        private const val SIG_NAME = 2
        private const val SIG_CODE_NAME = 4
        private const val CAUSE_TEXT = 1
        private const val THREAD_NAME = 2
        private const val THREAD_BACKTRACE = 4
        private const val FRAME_REL_PC = 1
        private const val FRAME_FUNCTION = 4
        private const val FRAME_OFFSET = 5
        private const val FRAME_FILE = 6

        /** Null when [bytes] is not a readable tombstone. */
        fun parse(bytes: ByteArray): TombstoneSummary? = runCatching {
            var tid = -1L
            var signal: String? = null
            var abort: String? = null
            val causes = ArrayList<String>()
            val threads = HashMap<Long, Pair<String?, List<String>>>()
            ProtoReader(bytes, 0, bytes.size).forEach { field, wire, r ->
                when {
                    field == T_TID && wire == ProtoReader.VARINT -> tid = r.varint()
                    field == T_SIGNAL && wire == ProtoReader.LEN -> signal = signal(r.message())
                    field == T_ABORT && wire == ProtoReader.LEN -> abort = r.string()
                    field == T_CAUSES && wire == ProtoReader.LEN -> cause(r.message())?.let { causes += it }
                    field == T_THREADS && wire == ProtoReader.LEN -> threadEntry(r.message())?.let { (id, t) -> threads[id] = t }
                    else -> r.skip(wire)
                }
            }
            val crashing = threads[tid] ?: threads.values.firstOrNull()
            TombstoneSummary(signal, abort, causes, crashing?.first, crashing?.second.orEmpty())
        }.getOrNull()?.takeIf { it.signal != null || it.abortMessage != null || it.frames.isNotEmpty() }

        private fun signal(r: ProtoReader): String? {
            var number: Long? = null
            var name: String? = null
            var code: String? = null
            r.forEach { field, wire, x ->
                when {
                    field == SIG_NUMBER && wire == ProtoReader.VARINT -> number = x.varint()
                    field == SIG_NAME && wire == ProtoReader.LEN -> name = x.string()
                    field == SIG_CODE_NAME && wire == ProtoReader.LEN -> code = x.string()
                    else -> x.skip(wire)
                }
            }
            if (number == null && name == null) return null
            return listOfNotNull(name, number?.let { "($it)" }, code?.takeIf { it.isNotBlank() }?.let { "code $it" }).joinToString(" ")
        }

        private fun cause(r: ProtoReader): String? {
            var text: String? = null
            r.forEach { field, wire, x -> if (field == CAUSE_TEXT && wire == ProtoReader.LEN) text = x.string() else x.skip(wire) }
            return text?.takeIf { it.isNotBlank() }
        }

        /** A map<uint32, Thread> entry: key 1, value 2. */
        private fun threadEntry(r: ProtoReader): Pair<Long, Pair<String?, List<String>>>? {
            var key = -1L
            var thread: Pair<String?, List<String>>? = null
            r.forEach { field, wire, x ->
                when {
                    field == 1 && wire == ProtoReader.VARINT -> key = x.varint()
                    field == 2 && wire == ProtoReader.LEN -> thread = thread(x.message())
                    else -> x.skip(wire)
                }
            }
            return thread?.let { key to it }
        }

        private fun thread(r: ProtoReader): Pair<String?, List<String>> {
            var name: String? = null
            val frames = ArrayList<String>()
            r.forEach { field, wire, x ->
                when {
                    field == THREAD_NAME && wire == ProtoReader.LEN -> name = x.string()
                    field == THREAD_BACKTRACE && wire == ProtoReader.LEN -> frames += frame(x.message())
                    else -> x.skip(wire)
                }
            }
            return name to frames
        }

        private fun frame(r: ProtoReader): String {
            var relPc = 0L
            var function: String? = null
            var offset = 0L
            var file: String? = null
            r.forEach { field, wire, x ->
                when {
                    field == FRAME_REL_PC && wire == ProtoReader.VARINT -> relPc = x.varint()
                    field == FRAME_FUNCTION && wire == ProtoReader.LEN -> function = x.string()
                    field == FRAME_OFFSET && wire == ProtoReader.VARINT -> offset = x.varint()
                    field == FRAME_FILE && wire == ProtoReader.LEN -> file = x.string()
                    else -> x.skip(wire)
                }
            }
            val where = function?.takeIf { it.isNotBlank() }?.let { if (offset > 0) "$it+$offset" else it } ?: "pc 0x" + java.lang.Long.toHexString(relPc)
            val lib = file?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            return if (lib != null) "$where ($lib)" else where
        }
    }
}

/** Minimal protobuf wire-format reader over a byte range. Pure. */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int, private val end: Int) {
    fun forEach(block: (field: Int, wire: Int, reader: ProtoReader) -> Unit) {
        while (pos < end) {
            val tag = varint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 7).toInt()
            require(field > 0) { "bad field" }
            block(field, wire, this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end && shift < 64) { "truncated varint" }
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun length(): Int {
        val n = varint()
        require(n >= 0 && n <= (end - pos)) { "bad length" }
        return n.toInt()
    }

    fun message(): ProtoReader {
        val n = length()
        val r = ProtoReader(buf, pos, pos + n)
        pos += n
        return r
    }

    fun string(): String {
        val n = length()
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun skip(wire: Int) {
        when (wire) {
            VARINT -> varint()
            FIXED64 -> { require(end - pos >= 8) { "truncated" }; pos += 8 }
            LEN -> {
                // Not `pos += length()`: that reads pos before length() moves past the prefix.
                val n = length()
                pos += n
            }
            FIXED32 -> { require(end - pos >= 4) { "truncated" }; pos += 4 }
            else -> throw IllegalArgumentException("unsupported wire type $wire")
        }
    }

    companion object {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LEN = 2
        const val FIXED32 = 5
    }
}
