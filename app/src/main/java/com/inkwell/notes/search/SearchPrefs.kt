package com.inkwell.notes.search

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Search screen preferences in a private file of this feature: the last [RecentQueries.MAX]
 * queries and whether semantic matching was left on. Reads and writes happen off the main thread.
 */
internal class SearchPrefs(private val context: Context) {
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    private val serializer = ListSerializer(String.serializer())

    suspend fun recent(): List<String> = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_RECENT, null) ?: return@withContext emptyList()
        try {
            Json.decodeFromString(serializer, raw).filter { it.isNotBlank() }.take(RecentQueries.MAX)
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun saveRecent(list: List<String>) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_RECENT, Json.encodeToString(serializer, list.take(RecentQueries.MAX))).apply()
    }

    suspend fun semanticOn(): Boolean = withContext(Dispatchers.IO) { prefs.getBoolean(KEY_SEMANTIC, false) }

    suspend fun setSemanticOn(on: Boolean) = withContext(Dispatchers.IO) { prefs.edit().putBoolean(KEY_SEMANTIC, on).apply() }

    companion object {
        private const val FILE = "inkwell_search"
        private const val KEY_RECENT = "recent_queries"
        private const val KEY_SEMANTIC = "semantic_on"
    }
}
