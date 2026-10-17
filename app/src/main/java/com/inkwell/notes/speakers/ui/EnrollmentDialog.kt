package com.inkwell.notes.speakers.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.inkwell.notes.R
import com.inkwell.notes.app.AppGraph
import com.inkwell.notes.audio.RecordingState
import com.inkwell.notes.audio.logic.LevelMeter
import com.inkwell.notes.core.model.Person
import com.inkwell.notes.speakers.SpeakerModelState
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic.Gate
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic.Phase
import com.inkwell.notes.speakers.ui.logic.EnrollmentLogic.Reason
import com.inkwell.notes.speakers.ui.logic.SpeakerNumbers
import com.inkwell.notes.speakers.ui.logic.SpeakerStatusLogic
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val R16 = RoundedCornerShape(16.dp)
private val R8 = RoundedCornerShape(8.dp)

/**
 * "Record a voice sample": about 15 s of the person reading a short paragraph, turned into a
 * voiceprint by graph.speakers.enroll. Recording starts only once the on-device models are
 * downloaded and voices are remembered (enroll stores nothing otherwise; the dialog offers the
 * download first). Never records while a document recording runs; the temporary audio file is
 * deleted whatever happens.
 */
@Composable
fun EnrollmentDialog(person: Person, onDismiss: () -> Unit) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val graph = remember(context) { AppGraph.get(context) }
    val recorder = remember { VoiceSampleRecorder(context.applicationContext) }
    var phase by remember { mutableStateOf<Phase>(Phase.Ready) }
    var startedAt by remember { mutableStateOf(0L) }
    val models by graph.speakers.modelState.collectAsStateWithLifecycle()
    val prefs by remember(graph) { graph.settings.settings.map { it.speakers } }.collectAsStateWithLifecycle(graph.settings.current.speakers)
    val gate = EnrollmentLogic.gate(models, prefs.rememberVoices)

    DisposableEffect(recorder) {
        VoiceSampleRecorder.sweep(context.applicationContext)
        onDispose { recorder.cancel() }
    }

    fun finish() {
        // The countdown and the Finish button can both call this; only the first stop counts.
        if (phase !is Phase.Recording) return
        val file = recorder.stop()
        if (file == null) {
            phase = Phase.TooLittleSpeech
            return
        }
        phase = Phase.Saving
        // What held when the clip was taken decides what a "false" from enroll means.
        val gateNow = EnrollmentLogic.gate(graph.speakers.modelState.value, graph.settings.current.speakers.rememberVoices)
        // On the app scope: the voiceprint is made (and the clip deleted) even if the dialog closes.
        graph.appScope.launch(Dispatchers.Main.immediate) {
            phase = try {
                val ok = withContext(Dispatchers.IO) { graph.speakers.enroll(person.id, file) }
                val exists = ok || withContext(Dispatchers.IO) { runCatching { graph.speakers.person(person.id) != null }.getOrDefault(true) }
                EnrollmentLogic.afterEnroll(ok, gateNow, exists)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w("Speakers", "Enrollment failed: ${t.javaClass.simpleName}")
                Phase.Failed(Reason.SAVE_FAILED)
            } finally {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { file.delete() }
            }
        }
    }

    fun begin() {
        if (graph.recording.state.value is RecordingState.Active) {
            phase = Phase.Failed(Reason.DOCUMENT_RECORDING)
            return
        }
        when (gate) {
            Gate.VoicesOff -> {
                phase = Phase.Failed(Reason.VOICES_OFF)
                return
            }
            is Gate.NeedsModels -> {
                phase = Phase.Failed(Reason.MODELS_MISSING)
                return
            }
            Gate.Ready -> Unit
        }
        phase = try {
            recorder.start()
            startedAt = SystemClock.elapsedRealtime()
            Phase.Recording(0, 0f)
        } catch (e: SecurityException) {
            Phase.Failed(Reason.PERMISSION)
        } catch (t: Throwable) {
            Log.w("Speakers", "Voice sample recorder failed: ${t.javaClass.simpleName}")
            Phase.Failed(Reason.MIC_BUSY)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) begin() else phase = Phase.Failed(Reason.PERMISSION)
    }

    fun requestStart() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) begin() else runCatching { permission.launch(Manifest.permission.RECORD_AUDIO) }.onFailure { phase = Phase.Failed(Reason.PERMISSION) }
    }

    fun fetchModels() {
        graph.appScope.launch {
            runCatching { graph.speakers.downloadModels() }
                .onFailure { Log.w("Speakers", "Could not queue the model download: ${it.javaClass.simpleName}") }
        }
    }

    val recording = phase is Phase.Recording
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        var level = 0f
        while (true) {
            delay(TICK_MS)
            // A document recording that starts meanwhile wins the microphone.
            if (graph.recording.state.value is RecordingState.Active) {
                recorder.cancel()
                phase = Phase.Failed(Reason.DOCUMENT_RECORDING)
                break
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            level = LevelMeter.smooth(level, recorder.level())
            phase = Phase.Recording(elapsed, level)
            if (EnrollmentLogic.shouldAutoStop(elapsed)) {
                finish()
                break
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (phase !is Phase.Recording) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = c.surface,
        shape = R16,
        title = { Text(stringResource(R.string.spk_enroll_title, person.name), style = MaterialTheme.typography.titleLarge, color = c.ink) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val p = phase) {
                    Phase.Saved -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = c.success)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.spk_enroll_saved, person.name), style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                    Phase.Saving -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.spk_enroll_saving), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                    }
                    else -> {
                        if (p !is Phase.Recording) GateNote(gate)
                        Text(stringResource(R.string.spk_enroll_body), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                        Text(
                            stringResource(R.string.spk_enroll_paragraph),
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.ink,
                            modifier = Modifier.fillMaxWidth().clip(R8).background(c.surfaceAlt).padding(14.dp),
                        )
                        if (p is Phase.Recording) RecordingMeter(p)
                        val problem = when (p) {
                            Phase.TooLittleSpeech -> stringResource(R.string.spk_enroll_too_little)
                            is Phase.Failed -> when (p.reason) {
                                Reason.MIC_BUSY -> stringResource(R.string.spk_enroll_mic_busy)
                                Reason.DOCUMENT_RECORDING -> stringResource(R.string.spk_enroll_doc_recording)
                                Reason.PERMISSION -> stringResource(R.string.spk_enroll_permission)
                                Reason.SAVE_FAILED -> stringResource(R.string.spk_enroll_failed)
                                // The gate note above already explains these two while they hold.
                                Reason.MODELS_MISSING -> if (gate is Gate.NeedsModels) null else stringResource(R.string.spk_enroll_needs_models, SpeakerStatusLogic.MODEL_DOWNLOAD_MB)
                                Reason.VOICES_OFF -> if (gate is Gate.VoicesOff) null else stringResource(R.string.spk_enroll_voices_off)
                                Reason.PERSON_GONE -> stringResource(R.string.spk_enroll_person_gone)
                            }
                            else -> null
                        }
                        if (problem != null) {
                            Text(problem, style = MaterialTheme.typography.bodyMedium, color = c.danger, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Lock, null, Modifier.padding(top = 2.dp).size(14.dp), tint = c.inkMuted)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.spk_enroll_privacy), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val p = phase) {
                is Phase.Recording -> {
                    val canFinish = EnrollmentLogic.canFinish(p.elapsedMs)
                    TextButton(onClick = { finish() }, enabled = canFinish) {
                        Text(stringResource(R.string.spk_enroll_stop), color = if (canFinish) c.accent else c.inkMuted, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Phase.Saving -> Unit
                Phase.Saved -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.spk_done), color = c.accent, style = MaterialTheme.typography.labelLarge)
                }
                // Before recording: settle the preconditions first (the model download), then start.
                else -> when {
                    EnrollmentLogic.offersDownload(gate) -> TextButton(onClick = { fetchModels() }) {
                        Text(
                            stringResource(R.string.spk_enroll_download_models, SpeakerStatusLogic.MODEL_DOWNLOAD_MB),
                            color = c.accent,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    !EnrollmentLogic.canStart(gate) -> Unit
                    p is Phase.Failed && p.reason == Reason.PERSON_GONE -> Unit
                    p is Phase.Ready -> TextButton(onClick = { requestStart() }) {
                        Text(stringResource(R.string.spk_enroll_start), color = c.accent, style = MaterialTheme.typography.labelLarge)
                    }
                    else -> TextButton(onClick = { requestStart() }) {
                        Text(stringResource(R.string.spk_try_again), color = c.accent, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        },
        dismissButton = {
            if (phase != Phase.Saved) {
                TextButton(onClick = {
                    recorder.cancel()
                    onDismiss()
                }) { Text(stringResource(R.string.spk_cancel), color = c.inkMuted, style = MaterialTheme.typography.labelLarge) }
            }
        },
    )
}

/** Why recording can't start yet: the models to download (with progress), or voices not remembered. */
@Composable
private fun GateNote(gate: Gate) {
    val c = Inkwell.colors
    when (gate) {
        Gate.Ready -> Unit
        Gate.VoicesOff -> Text(
            stringResource(R.string.spk_enroll_voices_off),
            style = MaterialTheme.typography.bodyMedium,
            color = c.danger,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        is Gate.NeedsModels -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
            when (val m = gate.models) {
                is SpeakerModelState.Downloading -> {
                    val done = SpeakerNumbers.megabytes(m.bytesDone).toInt()
                    val total = SpeakerNumbers.megabytes(m.bytesTotal).toInt()
                    Text(
                        if (m.bytesTotal > 0) stringResource(R.string.spk_models_downloading, done, total)
                        else stringResource(R.string.spk_models_downloading_unknown, done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.ink,
                    )
                    val fraction = SpeakerNumbers.fraction(m.bytesDone, m.bytesTotal)
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.surfaceAlt)
                    }
                }
                is SpeakerModelState.Failed -> Text(
                    stringResource(R.string.spk_enroll_models_failed, m.message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.danger,
                )
                else -> Text(
                    stringResource(R.string.spk_enroll_needs_models, SpeakerStatusLogic.MODEL_DOWNLOAD_MB),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink,
                )
            }
        }
    }
}

@Composable
private fun RecordingMeter(p: Phase.Recording) {
    val c = Inkwell.colors
    val levelLabel = stringResource(R.string.spk_enroll_level_cd)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(R8)
                .background(c.surfaceAlt)
                .semantics {
                    contentDescription = levelLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(p.level, 0f..1f)
                },
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(p.level.coerceIn(0.02f, 1f)).clip(R8).background(c.record))
        }
        LinearProgressIndicator(
            progress = { EnrollmentLogic.progress(p.elapsedMs) },
            modifier = Modifier.fillMaxWidth(),
            color = c.accent,
            trackColor = c.surfaceAlt,
        )
        Text(
            stringResource(R.string.spk_enroll_seconds_left, EnrollmentLogic.secondsLeft(p.elapsedMs)),
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = c.inkMuted,
        )
    }
}

private const val TICK_MS = 50L
