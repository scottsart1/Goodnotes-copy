package com.inkwell.notes.core.naming

import com.inkwell.notes.core.model.AutoNameStatus
import com.inkwell.notes.core.model.NamingPrefs

/**
 * The automatic "YYYYMMDD_Topic" rules as pure functions (no Android, no clock), so the
 * background job, the scheduler and the tests all agree on exactly when a document may be
 * renamed.
 *
 * - Only PENDING, QUEUED and INSUFFICIENT documents are ever renamed automatically.
 *   MANUAL, IMPORTED, DISABLED and DONE never are (DONE only via an explicit "rename now").
 * - Nothing is eligible before `createdAt + delayHours`, and nothing runs when automatic
 *   naming is switched off.
 * - INSUFFICIENT documents are re-checked at most every [INSUFFICIENT_RECHECK_MS] and at
 *   most [MAX_INSUFFICIENT_ATTEMPTS] times in total.
 */
object AutoNamingRules {
    const val HOUR_MS: Long = 60L * 60L * 1000L
    const val MAX_INSUFFICIENT_ATTEMPTS = 6
    const val INSUFFICIENT_RECHECK_MS: Long = 12L * HOUR_MS
    /** Transient AI failures tolerated before falling back to on-device inference. */
    const val MAX_AI_FAILURES = 3
    /** Spacing of retries for QUEUED documents when scheduling precise checks. */
    const val QUEUED_RETRY_MS: Long = HOUR_MS

    /** Statuses the background job may rename. */
    val AUTOMATIC: Set<AutoNameStatus> = setOf(AutoNameStatus.PENDING, AutoNameStatus.QUEUED, AutoNameStatus.INSUFFICIENT)

    /** When the naming window of a document created at [createdAt] closes. */
    fun windowEndsAt(createdAt: Long, prefs: NamingPrefs): Long =
        createdAt + prefs.delayHours.coerceAtLeast(0).toLong() * HOUR_MS

    /**
     * True when the background job may analyse and rename the document now.
     * [lastCheckAt] is the last time it was analysed automatically (null = never/unknown).
     */
    fun isEligible(
        status: AutoNameStatus,
        createdAt: Long,
        attempts: Int,
        lastCheckAt: Long?,
        now: Long,
        prefs: NamingPrefs,
    ): Boolean {
        if (!prefs.autoNameEnabled) return false
        if (now < windowEndsAt(createdAt, prefs)) return false
        return when (status) {
            AutoNameStatus.PENDING, AutoNameStatus.QUEUED -> true
            AutoNameStatus.INSUFFICIENT ->
                attempts < MAX_INSUFFICIENT_ATTEMPTS &&
                    // A last check "in the future" means the clock moved back; do not stall on it.
                    (lastCheckAt == null || lastCheckAt > now || now - lastCheckAt >= INSUFFICIENT_RECHECK_MS)
            AutoNameStatus.DONE, AutoNameStatus.MANUAL, AutoNameStatus.IMPORTED, AutoNameStatus.DISABLED -> false
        }
    }

    /**
     * The earliest time a background check is worth running for this document, or null when
     * it will never be renamed automatically. Used to schedule a precise one-off check in
     * addition to the hourly periodic job.
     */
    fun nextCheckAt(
        status: AutoNameStatus,
        createdAt: Long,
        attempts: Int,
        lastCheckAt: Long?,
        prefs: NamingPrefs,
    ): Long? {
        if (!prefs.autoNameEnabled) return null
        val windowEnd = windowEndsAt(createdAt, prefs)
        return when (status) {
            AutoNameStatus.PENDING -> windowEnd
            AutoNameStatus.QUEUED -> maxOf(windowEnd, (lastCheckAt ?: 0L) + QUEUED_RETRY_MS)
            AutoNameStatus.INSUFFICIENT ->
                if (attempts >= MAX_INSUFFICIENT_ATTEMPTS) null
                else maxOf(windowEnd, (lastCheckAt ?: 0L) + INSUFFICIENT_RECHECK_MS)
            else -> null
        }
    }

    /** How a document's naming state changes after an automatic analysis that did not rename it. */
    data class Update(val status: AutoNameStatus, val attempts: Int, val aiFailures: Int)

    /**
     * State after an unsuccessful automatic analysis: not enough content counts an attempt
     * (status INSUFFICIENT, title stays as is); a transient AI failure keeps the document
     * QUEUED for the next run and counts towards the local-inference fallback.
     */
    fun afterUnsuccessful(outcome: TopicOutcome, attempts: Int, aiFailures: Int): Update? = when (outcome) {
        is TopicOutcome.Found -> null
        TopicOutcome.Insufficient -> Update(AutoNameStatus.INSUFFICIENT, attempts + 1, aiFailures)
        TopicOutcome.RetryLater -> Update(AutoNameStatus.QUEUED, attempts, aiFailures + 1)
    }
}

/** Result of analysing a document's content for a topic. */
sealed interface TopicOutcome {
    data class Found(val topic: String, val source: Source) : TopicOutcome

    /** Not enough meaningful content to name the document. */
    data object Insufficient : TopicOutcome

    /** The AI provider failed transiently (offline, rate-limited, server error); try again later. */
    data object RetryLater : TopicOutcome

    enum class Source { AI, LOCAL }
}
