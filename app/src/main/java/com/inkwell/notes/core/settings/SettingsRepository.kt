package com.inkwell.notes.core.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.inkwell.notes.core.model.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * All user preferences as one typed [AppSettings] object, persisted as JSON with
 * DataStore. Secrets (API keys, vault password) never live here; see [SecretStore].
 */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store: DataStore<AppSettings> = DataStoreFactory.create(
        serializer = AppSettingsSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
        scope = scope,
        produceFile = { File(context.filesDir, "datastore/settings.json") },
    )

    /** Current settings. The first value is read synchronously at startup so screens never see defaults flash. */
    val settings: StateFlow<AppSettings> = store.data.stateIn(
        scope,
        SharingStarted.Eagerly,
        runBlocking { runCatching { store.data.first() }.getOrDefault(AppSettings()) },
    )

    val current: AppSettings get() = settings.value

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            coerceInputValues = true
            allowStructuredMapKeys = true
        }
    }
}

private object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings()

    override suspend fun readFrom(input: InputStream): AppSettings = try {
        SettingsRepository.json.decodeFromString(AppSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: Exception) {
        Log.e("Settings", "Corrupt settings file", e)
        throw CorruptionException("Cannot read settings", e)
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        output.write(SettingsRepository.json.encodeToString(AppSettings.serializer(), t).encodeToByteArray())
    }
}
