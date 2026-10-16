package com.inkwell.notes.audio.ui.dock

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.DockSide
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

internal val SliverVisibleHeight = 96.dp
internal val SliverTouchHeight = 140.dp

/**
 * The collapsed recorder: an [Dimens.recorderSliver]-wide strip flush with the screen edge,
 * rounded on its inner side, with a softly pulsing red mark. The surrounding box is the
 * larger invisible touch target ([Dimens.recorderSliverTouch] x [SliverTouchHeight]); the
 * caller attaches tap and drag handling to it through [modifier].
 */
@Composable
internal fun DockSliver(side: DockSide, paused: Boolean, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    val inner = 8.dp
    val shape = if (side == DockSide.RIGHT) {
        RoundedCornerShape(topStart = inner, bottomStart = inner)
    } else {
        RoundedCornerShape(topEnd = inner, bottomEnd = inner)
    }
    val pulse = rememberInfiniteTransition(label = "sliver")
    val alpha = pulse.animateFloat(
        initialValue = 1f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "sliverAlpha",
    )
    Box(modifier.size(Dimens.recorderSliverTouch, SliverTouchHeight)) {
        Box(
            Modifier
                .align(if (side == DockSide.RIGHT) Alignment.CenterEnd else Alignment.CenterStart)
                .size(Dimens.recorderSliver, SliverVisibleHeight)
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.outline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 4.dp, height = 22.dp)
                    .graphicsLayer { this.alpha = if (paused) 1f else alpha.value }
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (paused) c.warning else c.record),
            )
        }
    }
}
