package com.inkwell.notes

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.app.InkwellNavHost
import com.inkwell.notes.app.PendingNavigation
import com.inkwell.notes.settings.AppLockGate
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.ui.theme.InkwellTheme
import kotlinx.coroutines.launch

/**
 * Single activity. AppCompatActivity (a FragmentActivity) so BiometricPrompt works for the
 * app lock. Handles "Open with" / "Share to" for PDFs and images.
 */
class MainActivity : AppCompatActivity() {
    private val pendingNavigation = PendingNavigation()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = AppGraph.get(this)

        setContent {
            val settings by graph.settings.settings.collectAsStateWithLifecycle()
            LaunchedEffect(settings.privacy.blockScreenshots) {
                if (settings.privacy.blockScreenshots) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            InkwellTheme(settings.theme) {
                Box(Modifier.fillMaxSize().background(Inkwell.colors.ground)) {
                    AppLockGate {
                        InkwellNavHost(graph, pendingNavigation)
                    }
                }
            }
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == ACTION_OPEN_DOCUMENT) {
            // From our own notifications ("voices to name"). The activity is exported, so the id
            // is validated; opening still waits behind the app lock like any navigation.
            val docId = intent.getStringExtra(EXTRA_DOC_ID)?.takeIf { isValidDocId(it) } ?: return
            pendingNavigation.post { it.openDocumentById(docId) }
            return
        }
        val graph = AppGraph.get(this)
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            try {
                val mime = contentResolver.getType(uris.first()) ?: intent.type.orEmpty()
                val doc = if (mime.startsWith("image/")) graph.pdf.importImages(uris)
                else graph.pdf.importPdf(uris.first())
                pendingNavigation.post { it.openDocument(doc) }
            } catch (t: Throwable) {
                Log.e("MainActivity", "Import failed", t)
                Toast.makeText(this@MainActivity, "Couldn't import: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableExtra(name: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, Uri::class.java) else getParcelableExtra(name)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableArrayListExtra(name: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, Uri::class.java) else getParcelableArrayListExtra(name)) ?: emptyList()

    companion object {
        /** Opens the document whose id is in [EXTRA_DOC_ID]. */
        const val ACTION_OPEN_DOCUMENT = "com.inkwell.notes.OPEN_DOCUMENT"
        const val EXTRA_DOC_ID = "docId"

        private fun isValidDocId(id: String): Boolean =
            id.length in 1..128 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}
