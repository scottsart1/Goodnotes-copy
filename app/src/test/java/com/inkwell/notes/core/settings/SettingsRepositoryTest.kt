package com.inkwell.notes.core.settings

import android.content.ContextWrapper
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.SideButtonMode
import com.inkwell.notes.core.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Settings persistence on the JVM DataStore: defaults, corruption, unknown and invalid values. */
class SettingsRepositoryTest {
    private lateinit var tmp: File
    private lateinit var scope: CoroutineScope
    private val file get() = File(tmp, "files/datastore/settings.json")

    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = File(tmp, "files").apply { mkdirs() }
    }

    @Before fun setUp() {
        tmp = Files.createTempDirectory("inkwell-settings").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After fun tearDown() {
        scope.coroutineContext[Job]!!.cancel()
        tmp.deleteRecursively()
    }

    /** Simulates a process restart: the first DataStore must be released before a second one opens the file. */
    private suspend fun reopen(): SettingsRepository {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return SettingsRepository(context, scope)
    }

    @Test fun freshInstallHasDefaultsAndChangesPersistAcrossRestarts() = runTest {
        val repo = SettingsRepository(context, scope)
        assertEquals(AppSettings(), repo.current)
        assertTrue(repo.current.input.stylusOnlyInk)
        assertEquals(SideButtonMode.HOLD_TO_ERASE, repo.current.input.sideButton)
        assertEquals(24, repo.current.naming.delayHours)
        repo.update { it.copy(theme = ThemeMode.DARK, naming = it.naming.copy(delayHours = 48)) }
        // Read-after-write: the change is visible the moment update() returns.
        assertEquals(ThemeMode.DARK, repo.current.theme)
        assertEquals(ThemeMode.DARK, repo.settings.value.theme)
        val again = reopen()
        assertEquals(ThemeMode.DARK, again.current.theme)
        assertEquals(48, again.current.naming.delayHours)
        // Secrets never end up in the settings file.
        val text = file.readText()
        assertFalse(text.contains("password", ignoreCase = true))
        assertFalse(text.contains("apiKey", ignoreCase = true))
    }

    @Test fun corruptFileFallsBackToDefaultsAndIsRepaired() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("{\"theme\": \"DARK\", \"input\": {\"stylusOnlyInk\": tru")
        val repo = SettingsRepository(context, scope)
        assertEquals(AppSettings(), repo.current)
        repo.update { it.copy(onboardingDone = true) }
        assertTrue(repo.current.onboardingDone)
        val again = reopen()
        assertTrue(again.current.onboardingDone)
        assertEquals(ThemeMode.SYSTEM, again.current.theme)
    }

    @Test fun unknownKeysAndInvalidEnumValuesDoNotLoseTheOtherSettings() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText(
            """{"theme":"DARK","input":{"sideButton":"NOT_A_MODE","stylusOnlyInk":false,"futureFlag":true},"naming":{"delayHours":12},"brandNewSection":{"a":1}}""",
        )
        val repo = SettingsRepository(context, scope)
        val s = repo.current
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals("invalid enum coerces to its default", SideButtonMode.HOLD_TO_ERASE, s.input.sideButton)
        assertFalse(s.input.stylusOnlyInk)
        assertEquals(12, s.naming.delayHours)
    }

    @Test fun emptyFileIsTreatedAsDefaults() = runTest {
        file.parentFile!!.mkdirs()
        file.writeText("")
        val repo = SettingsRepository(context, scope)
        assertEquals(AppSettings(), repo.current)
        repo.update { it.copy(theme = ThemeMode.LIGHT) }
        assertEquals(ThemeMode.LIGHT, reopen().current.theme)
    }

    @Test fun concurrentUpdatesAreAllApplied() = runTest {
        val repo = SettingsRepository(context, scope)
        coroutineScope {
            repeat(20) {
                launch(Dispatchers.Default) {
                    repo.update { it.copy(naming = it.naming.copy(delayHours = it.naming.delayHours + 1)) }
                }
            }
        }
        assertEquals(44, repo.current.naming.delayHours)
        assertEquals(44, reopen().current.naming.delayHours)
    }
}
