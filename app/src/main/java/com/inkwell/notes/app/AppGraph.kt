package com.inkwell.notes.app

import android.content.Context
import com.inkwell.notes.ai.AiService
import com.inkwell.notes.ai.AiServiceImpl
import com.inkwell.notes.audio.PlaybackController
import com.inkwell.notes.audio.PlaybackControllerImpl
import com.inkwell.notes.audio.RecordingController
import com.inkwell.notes.audio.RecordingControllerImpl
import com.inkwell.notes.core.naming.AutoNamingService
import com.inkwell.notes.core.naming.AutoNamingServiceImpl
import com.inkwell.notes.core.settings.SecretStore
import com.inkwell.notes.core.settings.SettingsRepository
import com.inkwell.notes.core.storage.FileStore
import com.inkwell.notes.core.storage.LibraryRepository
import com.inkwell.notes.core.storage.LibraryRepositoryImpl
import com.inkwell.notes.core.storage.PageContentStore
import com.inkwell.notes.core.storage.db.AppDatabase
import com.inkwell.notes.export.BackupService
import com.inkwell.notes.export.BackupServiceImpl
import com.inkwell.notes.export.ExportService
import com.inkwell.notes.export.ExportServiceImpl
import com.inkwell.notes.export.SyncManager
import com.inkwell.notes.export.SyncManagerImpl
import com.inkwell.notes.export.ThumbnailService
import com.inkwell.notes.export.ThumbnailServiceImpl
import com.inkwell.notes.ocr.IndexingScheduler
import com.inkwell.notes.ocr.IndexingSchedulerImpl
import com.inkwell.notes.ocr.RecognitionService
import com.inkwell.notes.ocr.RecognitionServiceImpl
import com.inkwell.notes.ocr.SearchIndex
import com.inkwell.notes.ocr.SearchIndexImpl
import com.inkwell.notes.pdf.PdfService
import com.inkwell.notes.pdf.PdfServiceImpl
import com.inkwell.notes.speakers.SpeakerAudio
import com.inkwell.notes.speakers.SpeakerAudioImpl
import com.inkwell.notes.speakers.SpeakerService
import com.inkwell.notes.speakers.SpeakerServiceImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency graph (service locator). One instance per process, created in
 * [InkwellApp]. Implementations receive the graph and resolve their collaborators lazily,
 * so construction order never matters. Feature code obtains it with [AppGraph.get].
 */
class AppGraph(val context: Context) {
    /** Process-lifetime scope for background work that must outlive screens. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AppDatabase by lazy { AppDatabase.create(context) }
    val files: FileStore by lazy { FileStore(context) }
    val pageStore: PageContentStore by lazy { PageContentStore(files) }
    val settings: SettingsRepository by lazy { SettingsRepository(context, appScope) }
    val secrets: SecretStore by lazy { SecretStore(context) }

    /** Shared HTTP client: TLS only (network security config), no body logging (keys stay private). */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }

    val library: LibraryRepository by lazy { LibraryRepositoryImpl(db, files, pageStore, settings) }
    val naming: AutoNamingService by lazy { AutoNamingServiceImpl(this) }
    val ai: AiService by lazy { AiServiceImpl(this) }
    val recording: RecordingController by lazy { RecordingControllerImpl(this) }
    val playback: PlaybackController by lazy { PlaybackControllerImpl(this) }
    val recognition: RecognitionService by lazy { RecognitionServiceImpl(this) }
    val search: SearchIndex by lazy { SearchIndexImpl(this) }
    val indexing: IndexingScheduler by lazy { IndexingSchedulerImpl(this) }
    val pdf: PdfService by lazy { PdfServiceImpl(this) }
    val export: ExportService by lazy { ExportServiceImpl(this) }
    val thumbnails: ThumbnailService by lazy { ThumbnailServiceImpl(this) }
    val sync: SyncManager by lazy { SyncManagerImpl(this) }
    val backup: BackupService by lazy { BackupServiceImpl(this) }
    val speakers: SpeakerService by lazy { SpeakerServiceImpl(this) }
    val speakerAudio: SpeakerAudio by lazy { SpeakerAudioImpl(this) }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun init(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext).also { instance = it }
        }

        /** The process graph. Valid after [InkwellApp.onCreate]. */
        fun get(): AppGraph = checkNotNull(instance) { "AppGraph not initialised" }

        fun get(context: Context): AppGraph = init(context)
    }
}
