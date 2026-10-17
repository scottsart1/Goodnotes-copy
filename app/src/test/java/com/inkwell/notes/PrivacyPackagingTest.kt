package com.inkwell.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Build and manifest rules that keep voice data on the device and the sherpa-onnx native
 * code packaged correctly. These are read from the module's files (tests run with app/ as
 * the working directory) so a careless edit fails here instead of on the tablet.
 */
class PrivacyPackagingTest {
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> {
        val out = ArrayList<Element>()
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) out += nodes.item(i) as Element
        return out
    }

    private fun Element.attr(name: String): String? = getAttribute(name).takeIf { hasAttribute(name) }

    // ------------------------------------------------------------------ sherpa-onnx packaging

    @Test fun onlyArm64NativeCodeIsPackaged() {
        val gradle = File("build.gradle.kts").readText()
        val abi = Regex("abiFilters\\s*\\+?=\\s*listOf\\(([^)]*)\\)").find(gradle)
        assertTrue("abiFilters must be declared", abi != null)
        val abis = Regex("\"([^\"]+)\"").findAll(abi!!.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals("the Tab S11 Ultra is arm64 only; other ABIs just add megabytes", listOf("arm64-v8a"), abis)
    }

    @Test fun unusedSherpaApiLibrariesAreExcludedFromJniLibs() {
        val gradle = File("build.gradle.kts").readText()
        val jni = Regex("jniLibs\\s*\\{(.*?)\\n\\s*\\}", RegexOption.DOT_MATCHES_ALL).find(gradle)
        assertTrue("packaging.jniLibs block missing", jni != null)
        val block = jni!!.groupValues[1]
        assertTrue(block.contains("libsherpa-onnx-c-api.so"))
        assertTrue(block.contains("libsherpa-onnx-cxx-api.so"))
        // The two libraries the JNI bridge actually loads must stay.
        assertFalse(block.contains("libsherpa-onnx-jni"))
        assertFalse(block.contains("libonnxruntime"))
        assertTrue("sherpa-onnx is a declared dependency", gradle.contains("libs.sherpa.onnx"))
    }

    @Test fun proguardKeepsSherpaClassesTheNativeCodeLooksUpByName() {
        val rules = File("proguard-rules.pro").readLines().map { it.trim() }
        assertTrue(rules.any { it.startsWith("-keep class com.k2fsa.sherpa.onnx.**") && it.contains("{ *; }") })
        // Serializers for the app's own models are found by reflection too.
        assertTrue(rules.any { it.contains("kotlinx.serialization.KSerializer serializer(...)") })
    }

    // ------------------------------------------------------------------ what leaves the device

    @Test fun fileProviderNeverExposesVoiceDataOrTheWholeFilesDir() {
        val paths = xml("src/main/res/xml/file_paths.xml")
        val entries = listOf("files-path", "cache-path", "external-path", "external-files-path", "external-cache-path", "external-media-path", "root-path")
            .flatMap { tag -> paths.children(tag).map { tag to (it.attr("path") ?: "") } }
        assertTrue(entries.isNotEmpty())
        assertTrue("root-path would expose no_backup/", entries.none { it.first == "root-path" })
        for ((tag, path) in entries) {
            val p = path.trimEnd('/')
            assertTrue("$tag '$path' exposes a whole directory tree", p.isNotEmpty() && p != ".")
            assertFalse(p.contains("no_backup"))
            assertFalse(p.contains("voices"))
            assertFalse(p.contains("datastore"))
        }
        // Documents are shared from files/docs/ only; the FileProvider is private to the app.
        assertEquals(listOf("docs/"), paths.children("files-path").map { it.attr("path") })
    }

    @Test fun cloudBackupAndDeviceTransferExcludeEveryDomainThatHoldsUserData() {
        val rules = xml("src/main/res/xml/data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val node = rules.children(section).singleOrNull()
            assertTrue("$section section missing", node != null)
            val excludedWhole = node!!.children("exclude").filter { it.attr("path") == null }.map { it.attr("domain") }.toSet()
            // Excluding "root" does not cover files/, databases/ or shared_prefs/: those are their own domains.
            // Notes and the Room database (people's names, transcripts) must stay out of both; on Android 12+
            // some makers' device-to-device transfer ignores allowBackup="false".
            assertEquals("$section must exclude every domain", setOf("root", "file", "database", "sharedpref"), excludedWhole)
            assertTrue("$section must not include anything", node.children("include").isEmpty())
        }
    }

    @Test fun manifestKeepsBackupOffAndComponentsPrivate() {
        val manifest = xml("src/main/AndroidManifest.xml")
        val app = manifest.children("application").single()
        val ns = "http://schemas.android.com/apk/res/android"
        assertEquals("false", app.getAttributeNS(ns, "allowBackup"))
        assertEquals("false", app.getAttributeNS(ns, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(ns, "dataExtractionRules"))
        val service = app.children("service").single { it.getAttributeNS(ns, "name") == ".audio.RecordingService" }
        assertEquals("false", service.getAttributeNS(ns, "exported"))
        assertEquals("microphone", service.getAttributeNS(ns, "foregroundServiceType"))
        val provider = app.children("provider").single()
        assertEquals("false", provider.getAttributeNS(ns, "exported"))
        assertEquals("true", provider.getAttributeNS(ns, "grantUriPermissions"))
        // The single activity is exported (launcher, share targets, notification taps); it validates its extras.
        val activity = app.children("activity").single()
        assertEquals(".MainActivity", activity.getAttributeNS(ns, "name"))
        assertEquals("true", activity.getAttributeNS(ns, "exported"))
        assertTrue("no other components may be exported", app.children("receiver").none { it.getAttributeNS(ns, "exported") == "true" })
    }

    @Test fun noOpenDocumentIntentFilterInvitesOtherApps() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        // The notification's action is handled when it arrives, but it is not advertised: other
        // apps cannot discover it through an intent filter.
        assertFalse(manifest.contains(MainActivity.ACTION_OPEN_DOCUMENT))
    }
}
