package com.inkwell.notes.audio.ui.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DockSide
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.delay

internal val CapsuleWidth = 64.dp
internal val CapsuleHeight = 232.dp
internal val CapsuleShape = RoundedCornerShape(16.dp)

/**
 * The expanded recording control: live dot, elapsed time, level meter, Pause/Resume,
 * Stop (needs a second tap) and the chevron that tucks it into the edge. Drag handling is
 * attached by the caller through [modifier].
 */
@Composable
internal fun DockCapsule(
    side: DockSide,
    paused: Boolean,
    time: () -> String,
    level: () -> Float,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Inkwell.colors
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(STOP_CONFIRM_MS)
            armed = false
        }
    }
    val blink = rememberBlink(paused && !armed)
    val timeText = time()
    val stateDescription = stringResource(if (paused) R.string.audio_dock_paused_cd else R.string.audio_dock_recording_cd, timeText)

    Column(
        modifier
            .size(CapsuleWidth, CapsuleHeight)
            .shadow(8.dp, CapsuleShape, clip = false, ambientColor = c.pageShadow, spotColor = c.pageShadow)
            .clip(CapsuleShape)
            .background(c.surface)
            .border(1.dp, c.outline, CapsuleShape)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        RecordingDot(paused = paused)
        Spacer(Modifier.height(8.dp))
        val label = if (armed) stringResource(R.string.audio_dock_stop_short) else timeText
        Text(
            text = label,
            style = timeStyle(label.length),
            color = if (armed) c.record else c.ink,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .height(18.dp)
                .graphicsLayer { alpha = blink.value }
                .semantics { contentDescription = stateDescription },
        )
        Spacer(Modifier.height(10.dp))
        LevelBars(level = level, paused = paused, modifier = Modifier.clearAndSetSemantics {})
        Spacer(Modifier.height(8.dp))
        CapsuleButton(
            icon = if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
            description = stringResource(if (paused) R.string.audio_dock_resume else R.string.audio_dock_pause),
            onClick = {
                armed = false
                onPauseResume()
            },
        )
        CapsuleButton(
            icon = Icons.Outlined.Stop,
            description = stringResource(if (armed) R.string.audio_dock_stop_confirm else R.string.audio_dock_stop),
            tint = if (armed) c.surface else c.record,
            background = if (armed) c.record else Color.Transparent,
            onClick = {
                if (armed) {
                    armed = false
                    onStop()
                } else {
                    armed = true
                }
            },
        )
        CapsuleButton(
            icon = if (side == DockSide.RIGHT) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            description = stringResource(R.string.audio_dock_collapse),
            tint = c.inkMuted,
            onClick = {
                armed = false
                onCollapse()
            },
        )
    }
}

@Composable
private fun timeStyle(length: Int): TextStyle {
    val base = if (length > 5) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    return base.copy(fontFeatureSettings = "tnum")
}

@Composable
private fun CapsuleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Inkwell.colors.ink,
    background: Color = Color.Transparent,
) {
    Box(
        Modifier
            .size(Dimens.minTouch)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.icon), tint = tint)
        }
    }
}

private const val STOP_CONFIRM_MS = 3_000L
