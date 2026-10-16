package com.inkwell.notes.library.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.inkwell.notes.R
import com.inkwell.notes.export.ExportFormat
import com.inkwell.notes.library.LibraryViewModel
import com.inkwell.notes.library.UiText

/** System pickers and the document scanner used by the New menu and bulk export. */
@Stable
internal class LibraryLaunchers(
    val importPdf: () -> Unit,
    val importImages: () -> Unit,
    val scanDocument: () -> Unit,
    val importPackage: () -> Unit,
    val exportTo: (docIds: List<String>, format: ExportFormat) -> Unit,
)

private const val TAG = "LibraryLaunchers"

/** Holder for the export waiting for its folder; survives recomposition, not process death. */
private class PendingExport {
    var docIds: List<String> = emptyList()
    var format: ExportFormat = ExportFormat.PDF
}

@Composable
internal fun rememberLibraryLaunchers(vm: LibraryViewModel): LibraryLaunchers {
    val context = LocalContext.current
    val pending = remember { PendingExport() }

    val pdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importPdfs(uris)
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) vm.importImages(uris)
    }
    val packages = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importPackage(uri)
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdf = try {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        } catch (t: Throwable) {
            Log.w(TAG, "Unreadable scanner result: ${t.javaClass.simpleName}")
            null
        }
        if (pdf != null) vm.importScan(pdf) else vm.message(UiText.Res(R.string.lib_err_scan_empty))
    }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val ids = pending.docIds
        pending.docIds = emptyList()
        if (uri != null && ids.isNotEmpty()) vm.exportMany(ids, pending.format, uri)
    }

    fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: ActivityNotFoundException) {
            vm.message(UiText.Res(R.string.lib_err_no_picker))
        } catch (e: Exception) {
            Log.w(TAG, "Picker failed: ${e.javaClass.simpleName}")
            vm.message(UiText.Res(R.string.lib_err_no_picker))
        }
    }

    return remember(vm, context) {
        LibraryLaunchers(
            importPdf = { guard { pdfs.launch(arrayOf("application/pdf")) } },
            importImages = {
                guard { images.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            },
            scanDocument = {
                val activity = context.findActivity()
                if (activity == null) {
                    vm.message(UiText.Res(R.string.lib_err_scanner_failed))
                } else {
                    startScanner(activity, vm) { sender -> guard { scan.launch(IntentSenderRequest.Builder(sender).build()) } }
                }
            },
            // .inkwell files have no registered MIME type, so providers report them as zip or octet-stream.
            importPackage = { guard { packages.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) } },
            exportTo = { ids, format ->
                pending.docIds = ids
                pending.format = format
                guard { tree.launch(null) }
            },
        )
    }
}

/**
 * Starts the ML Kit document scanner (full mode with filters and cleanup, gallery import
 * allowed, PDF result). The scanner is a Google Play services module that may still be
 * downloading; that case gets its own message.
 */
private fun startScanner(activity: Activity, vm: LibraryViewModel, launch: (android.content.IntentSender) -> Unit) {
    val options = try {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "Scanner options failed: ${t.javaClass.simpleName}")
        vm.message(UiText.Res(R.string.lib_err_scanner_unavailable))
        return
    }
    try {
        GmsDocumentScanning.getClient(options)
            .getStartScanIntent(activity)
            .addOnSuccessListener(activity) { sender -> launch(sender) }
            .addOnFailureListener(activity) { e ->
                Log.w(TAG, "Scanner unavailable: ${e.javaClass.simpleName}")
                val unavailable = e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE
                vm.message(UiText.Res(if (unavailable) R.string.lib_err_scanner_unavailable else R.string.lib_err_scanner_failed), long = true)
            }
    } catch (t: Throwable) {
        // Missing Play services or an incompatible module.
        Log.w(TAG, "Scanner failed to start: ${t.javaClass.simpleName}")
        vm.message(UiText.Res(R.string.lib_err_scanner_unavailable), long = true)
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
