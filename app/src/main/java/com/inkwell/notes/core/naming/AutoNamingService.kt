package com.inkwell.notes.core.naming

import com.inkwell.notes.app.AppGraph

/**
 * Infers "YYYYMMDD_Topic" names for untitled documents once [com.inkwell.notes.core.model.NamingPrefs.delayHours]
 * have passed since creation. Runs as deferrable background work (WorkManager), survives the
 * app being closed, queues itself while offline, and never touches MANUAL or IMPORTED documents
 * unless the user explicitly asks via [renameNow].
 */
interface AutoNamingService {
    /** Schedules periodic background checks (idempotent; call at app start). */
    fun schedule()

    /** Processes every document whose naming window has elapsed. Returns how many were renamed. */
    suspend fun runDue(): Int

    /**
     * User-requested rename ("Suggest a new name"): infers a topic now, even for MANUAL/IMPORTED
     * documents, and applies it. Returns the new title, or null if there was not enough content.
     */
    suspend fun renameNow(docId: String): String?

    /** Infers a topic without applying it (for previews). */
    suspend fun suggestTopic(docId: String): String?
}

/** SCAFFOLD STUB — replaced by the naming implementation. */
class AutoNamingServiceImpl(private val graph: AppGraph) : AutoNamingService {
    override fun schedule() {}
    override suspend fun runDue(): Int = 0
    override suspend fun renameNow(docId: String): String? = null
    override suspend fun suggestTopic(docId: String): String? = null
}
