package com.inkwell.notes.whiteboard.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.editor.logic.MicPermissions
import com.inkwell.notes.editor.logic.MicRequestOutcome
import com.inkwell.notes.editor.ui.dialogs.MicRationaleDialog
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.whiteboard.BoardAudio
import com.inkwell.notes.whiteboard.RecordingDialog
import com.inkwell.notes.whiteboard.kit.KitIconButton
import com.inkwell.notes.whiteboard.logic.RecordControl
import com.inkwell.notes.whiteboard.logic.RecordLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The top-bar Record control: a mic while idle (or while another document records), a live
 * dot with the elapsed time while this board records. Tapping it always goes through
 * [BoardAudio.onRecord], which picks start, controls or "recording elsewhere". It observes
 * the recording itself, so only this button recomposes as the time ticks.
 */
@Composable
fun BoardRecordButton(docId: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val state = graph.recording.state.collectAsStateWithLifecycle()
    // Whole seconds: the 20 Hz level updates of the recorder don't recompose anything.
    val control by remember(docId) { derivedStateOf { RecordLogic.control(state.value, docId) } }
    RecordButtonContent(control, onClick, modifier)
}

@Composable
private fun RecordButtonContent(control: RecordControl, onClick: () -> Unit, modifier: Modifier) {
    if (control !is RecordControl.Here) {
        KitIconButton(Icons.Outlined.Mic, stringResource(R.string.wb_audio_record), onClick, modifier)
        return
    }
    val c = Inkwell.colors
    val time = RecordLogic.clock(control.elapsedSeconds)
    val description = stringResource(if (control.paused) R.string.wb_audio_paused_cd else R.string.wb_audio_recording_cd, time)
    Box(
        modifier
            .heightIn(min = Dimens.minTouch)
            .widthIn(min = Dimens.minTouch)
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .clearAndSetSemantics { } // the button's description already says the time
                .clip(RoundedCornerShape(50))
                .background(if (control.paused) c.surfaceAlt else c.record.copy(alpha = if (c.isDark) 0.20f else 0.10f))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(paused = control.paused)
            Spacer(Modifier.width(8.dp))
            Text(
                time,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = if (control.paused) c.inkMuted else c.ink,
                maxLines = 1,
            )
        }
    }
}

/** Pulsing red while recording, a hollow amber ring while paused. */
@Composable
private fun LiveDot(paused: Boolean, size: Dp = 9.dp) {
    val c = Inkwell.colors
    val pulse = rememberInfiniteTransition(label = "wbRecDot")
    val alpha = pulse.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "wbRecDotAlpha",
    )
    Canvas(Modifier.size(size).graphicsLayer { this.alpha = if (paused) 1f else alpha.value }) {
        val r = this.size.minDimension / 2f
        if (paused) {
            val w = 1.6.dp.toPx()
            drawCircle(c.warning, radius = r - w / 2f, style = Stroke(width = w))
        } else {
            drawCircle(c.record, radius = r)
        }
    }
}

/**
 * Registers the microphone permission prompt (with notifications on Android 13+, so the
 * recording notification with Pause and Stop shows) and launches it when [audio] asks.
 * Nothing is requested until the user taps Record.
 */
@Composable
fun MicPermissionEffect(audio: BoardAudio) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // An empty result means the request was cancelled (e.g. another prompt was showing).
        val activity = context.findActivity()
        val rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ?: true
        when (val outcome = MicPermissions.outcome(result, hasPermission(context, Manifest.permission.RECORD_AUDIO), rationale)) {
            MicRequestOutcome.Granted -> audio.onMicResult(granted = true, permanentlyDenied = false)
            is MicRequestOutcome.Denied -> audio.onMicResult(granted = false, permanentlyDenied = outcome.permanently)
            MicRequestOutcome.Cancelled -> Unit
        }
    }
    LaunchedEffect(audio) {
        audio.micRequests.collect {
            val hasNotifications = Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            val perms = MicPermissions.toRequest(Build.VERSION.SDK_INT, hasNotifications)
            try {
                launcher.launch(perms.toTypedArray())
            } catch (e: ActivityNotFoundException) {
                audio.onMicResult(granted = false, permanentlyDenied = true)
            }
        }
    }
}

/** The board's current recording dialog, if any. */
@Composable
fun BoardRecordingDialogs(audio: BoardAudio, docId: String) {
    val dialog = audio.dialog ?: return
    val context = LocalContext.current
    val dismiss = { audio.dialog = null }
    when (dialog) {
        is RecordingDialog.MicRationale -> MicRationaleDialog(
            permanentlyDenied = dialog.permanentlyDenied,
            onRetry = audio::requestMic,
            onOpenSettings = {
                audio.dialog = null
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            onDismiss = dismiss,
        )
        RecordingDialog.Controls -> RecordingControlsDialog(audio, docId, dismiss)
        is RecordingDialog.Elsewhere -> RecordingElsewhereDialog(dialog.otherDocId, onConfirm = audio::stopOtherAndRecordHere, onDismiss = dismiss)
    }
}

@Composable
private fun RecordingControlsDialog(audio: BoardAudio, docId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val state = graph.recording.state.collectAsStateWithLifecycle()
    val control by remember(docId) { derivedStateOf { RecordLogic.control(state.value, docId) } }
    val here = control as? RecordControl.Here
    if (here == null) {
        // The recording ended (or moved) while the dialog was open.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(stringResource(R.string.wb_audio_controls_title), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(paused = here.paused, size = 11.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        RecordLogic.clock(here.elapsedSeconds) + if (here.paused) " · " + stringResource(R.string.wb_audio_paused) else "",
                        style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                        color = c.ink,
                    )
                }
                Text(stringResource(R.string.wb_audio_controls_hint), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
            }
        },
        confirmButton = {
            TextButton(onClick = audio::stop) { Text(stringResource(R.string.wb_audio_stop), color = c.record) }
        },
        dismissButton = {
            TextButton(onClick = { audio.togglePause(here.paused) }) {
                Text(stringResource(if (here.paused) R.string.wb_audio_resume else R.string.wb_audio_pause), color = c.accent)
            }
        },
    )
}

@Composable
private fun RecordingElsewhereDialog(otherDocId: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val title by produceState<String?>(null, otherDocId) {
        value = withContext(Dispatchers.IO) { runCatching { graph.library.getDocument(otherDocId)?.title }.getOrNull() }?.takeIf { it.isNotBlank() }
    }
    val c = Inkwell.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(stringResource(R.string.wb_audio_elsewhere_title), color = c.ink) },
        text = {
            Text(
                title?.let { stringResource(R.string.wb_audio_elsewhere_body_named, it) } ?: stringResource(R.string.wb_audio_elsewhere_body),
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.wb_audio_elsewhere_action), color = c.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.wb_audio_cancel), color = c.inkMuted) } },
    )
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
