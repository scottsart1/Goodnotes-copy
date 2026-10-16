package com.inkwell.notes.audio.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.audio.PlaybackState
import com.inkwell.notes.audio.logic.AudioText
import com.inkwell.notes.core.model.Recording
import com.inkwell.notes.ui.theme.Inkwell
import java.util.Locale

internal val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** Waveform, transport, speed and "Show notes written here" for one recording. */
@Composable
internal fun PlayerCard(
    recording: Recording,
    playback: PlaybackState,
    waveform: FloatArray?,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onShowNotes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    val mine = playback.recording?.id == recording.id
    val position = if (mine) playback.positionMs else 0L
    val duration = (if (mine && playback.durationMs > 0) playback.durationMs else recording.durationMs).coerceAtLeast(0)
    val playing = mine && playback.playing
    val tnum = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")

    Column(
        modifier
            .fillMaxWidth()
            .clip(R12)
            .background(c.surface)
            .border(1.dp, c.outline, R12)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WaveformBar(
            values = waveform,
            positionMs = position,
            durationMs = duration,
            onSeek = onSeek,
            description = stringResource(R.string.audio_waveform_cd, AudioText.clock(position), AudioText.clock(duration)),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(AudioText.clock(position), style = tnum, color = c.ink)
            Spacer(Modifier.weight(1f))
            Text(AudioText.clock(duration), style = tnum, color = c.inkMuted)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            AudioIconButton(Icons.Outlined.Replay10, stringResource(R.string.audio_back_10), { onSkip(-SKIP_MS) }, tint = c.ink)
            Spacer(Modifier.width(16.dp))
            val label = stringResource(if (playing) R.string.audio_pause else R.string.audio_play)
            Box(
                Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, label, Modifier.size(24.dp), tint = c.onAccent)
            }
            Spacer(Modifier.width(16.dp))
            AudioIconButton(Icons.Outlined.Forward10, stringResource(R.string.audio_forward_10), { onSkip(SKIP_MS) }, tint = c.ink)
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            for (s in SPEEDS) {
                val text = speedLabel(s)
                AudioChip(
                    label = text,
                    selected = kotlin.math.abs(playback.speed - s) < 0.01f,
                    onClick = { onSpeed(s) },
                    contentDescription = stringResource(R.string.audio_speed_cd, text),
                )
            }
        }
        if (mine && playback.error != null) {
            Text(playback.error, style = MaterialTheme.typography.bodySmall, color = c.danger)
        }
        AudioSecondaryButton(
            label = stringResource(R.string.audio_show_notes),
            onClick = onShowNotes,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun speedLabel(s: Float): String {
    val txt = if (s == s.toInt().toFloat()) s.toInt().toString() else String.format(Locale.ROOT, "%s", s).trimEnd('0').trimEnd('.')
    return "${txt}x"
}

private const val SKIP_MS = 10_000L
