package com.inkwell.notes.speakers.logic

/**
 * Links the voices found separately in each analysis window into the voices of the whole
 * recording. Pure.
 *
 * Each window is diarized on its own (to keep memory bounded), so "speaker 0" of window 3 is
 * unrelated to "speaker 0" of window 4. Every window-local voice has an embedding averaged
 * over its speech. Voices with enough speech ([MIN_ANCHOR_MS]) are clustered agglomeratively:
 * the two most similar clusters (cosine of their speech-weighted centroids) merge while that
 * similarity is at least the threshold. Voices with less speech are too unreliable to found a
 * voice of their own and join their most similar cluster. With [expectedSpeakers] > 0 the
 * closest clusters keep merging until at most that many remain.
 */
object ClusterLinker {
    /** Window-local voices with less speech join an existing voice instead of founding one. */
    const val MIN_ANCHOR_MS = 1_500L

    data class LocalVoice(
        /** Unique across the recording, e.g. "w3:1". */
        val id: String,
        val speechMs: Long,
        /** L2-normalised embedding, or null when there was too little audio to compute one. */
        val embedding: FloatArray?,
        /** First appearance on the recording timeline (orders the result). */
        val firstStartMs: Long = 0,
    )

    data class GlobalVoice(
        val members: List<String>,
        val speechMs: Long,
        /** Speech-weighted, L2-normalised centroid. */
        val centroid: FloatArray,
    )

    data class Result(
        /** Local id to index into [voices]; local voices without an embedding are absent. */
        val assignment: Map<String, Int>,
        /** Ordered by first appearance. */
        val voices: List<GlobalVoice>,
    )

    private class Cluster(val members: MutableList<LocalVoice>) {
        var centroid: FloatArray = centroidOf(members)
        val speechMs: Long get() = members.sumOf { it.speechMs }
        val firstStartMs: Long get() = members.minOf { it.firstStartMs }

        fun absorb(other: Cluster) {
            members += other.members
            centroid = centroidOf(members)
        }
    }

    fun link(
        voices: List<LocalVoice>,
        threshold: Float = VoiceScores.LINK_THRESHOLD,
        expectedSpeakers: Int = 0,
    ): Result {
        val withEmbedding = voices.filter { it.embedding != null && it.embedding.isNotEmpty() && VectorMath.norm(it.embedding) > 0f }
        val dim = withEmbedding.firstOrNull()?.embedding?.size ?: return Result(emptyMap(), emptyList())
        val usable = withEmbedding.filter { it.embedding!!.size == dim }
        var anchors = usable.filter { it.speechMs >= MIN_ANCHOR_MS }
        var minors = usable.filter { it.speechMs < MIN_ANCHOR_MS }
        if (anchors.isEmpty()) {
            // Only short voices: let the longest found the clusters.
            anchors = listOf(minors.maxWith(compareBy<LocalVoice> { it.speechMs }.thenBy { it.id }))
            minors = minors - anchors.toSet()
        }

        val clusters = anchors.sortedBy { it.firstStartMs }.map { Cluster(mutableListOf(it)) }.toMutableList()
        mergeWhile(clusters) { best, count -> best >= threshold || (expectedSpeakers in 1 until count) }

        for (m in minors) {
            val best = clusters.maxWith(compareBy<Cluster> { VectorMath.cosine(it.centroid, m.embedding!!) })
            best.absorb(Cluster(mutableListOf(m)))
        }
        // Absorbing short voices moves centroids only slightly; still honour the expected count.
        if (expectedSpeakers > 0) mergeWhile(clusters) { _, count -> count > expectedSpeakers }

        val ordered = clusters.sortedWith(compareBy<Cluster> { it.firstStartMs }.thenBy { it.members.first().id })
        val assignment = HashMap<String, Int>()
        ordered.forEachIndexed { i, c -> c.members.forEach { assignment[it.id] = i } }
        return Result(assignment, ordered.map { c -> GlobalVoice(c.members.map { it.id }, c.speechMs, c.centroid) })
    }

    /** Merges the most similar pair while [proceed] (best similarity, cluster count) says so. */
    private fun mergeWhile(clusters: MutableList<Cluster>, proceed: (Float, Int) -> Boolean) {
        while (clusters.size > 1) {
            var bi = -1
            var bj = -1
            var best = Float.NEGATIVE_INFINITY
            for (i in clusters.indices) for (j in i + 1 until clusters.size) {
                val s = VectorMath.cosine(clusters[i].centroid, clusters[j].centroid)
                if (s > best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
            if (bi < 0 || !proceed(best, clusters.size)) return
            clusters[bi].absorb(clusters.removeAt(bj))
        }
    }

    private fun centroidOf(members: List<LocalVoice>): FloatArray =
        VectorMath.weightedMean(members.map { it.embedding!! }, members.map { it.speechMs.coerceAtLeast(1).toDouble() })
            ?: members.first().embedding!!
}
