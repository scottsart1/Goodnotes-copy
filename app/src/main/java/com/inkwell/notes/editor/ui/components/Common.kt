package com.inkwell.notes.editor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.inkwell.notes.editor.logic.ColorMath
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** A floating chrome surface: surface colour, 1dp outline, barely-there shadow. */
@Composable
fun FloatingSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    color: Color = Inkwell.colors.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color,
        contentColor = Inkwell.colors.ink,
        border = BorderStroke(1.dp, Inkwell.colors.outline),
        shadowElevation = 1.dp,
        content = content,
    )
}

/**
 * A 20dp icon in a [Dimens.minTouch] touch target with a [visual]-sized highlight. Selected
 * buttons get the accent container; [temporary] shows an accent ring (S Pen side button).
 */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    temporary: Boolean = false,
    tint: Color? = null,
    visual: Dp = Dimens.toolButton,
    iconSize: Dp = Dimens.icon,
    touch: Dp = Dimens.minTouch,
    onLongClick: (() -> Unit)? = null,
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    val c = Inkwell.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(touch)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        val bg = when {
            selected -> c.accentContainer
            else -> Color.Transparent
        }
        Box(
            Modifier
                .size(visual)
                .clip(CircleShape)
                .background(bg)
                .then(if (temporary) Modifier.border(1.5.dp, c.accent, CircleShape) else Modifier)
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
                tint = tint ?: when {
                    !enabled -> c.inkMuted.copy(alpha = 0.38f)
                    selected || temporary -> c.accent
                    else -> c.ink
                },
            )
            badge?.invoke(this)
        }
    }
}

/** Thin separator inside pills and menus. */
@Composable
fun PillDivider(vertical: Boolean, modifier: Modifier = Modifier) {
    val c = Inkwell.colors.outline
    if (vertical) Box(modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(c))
    else Box(modifier.padding(vertical = 4.dp).height(1.dp).width(20.dp).background(c))
}

/** A round colour swatch with selection ring; 44dp touch target. */
@Composable
fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val c = Inkwell.colors
    Box(
        modifier
            .size(Dimens.minTouch)
            .clip(CircleShape)
            .combinedClickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.contentDescription = contentDescription; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size + 6.dp)
                .border(if (selected) 2.dp else 0.dp, if (selected) c.accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(1.dp, if (ColorMath.isLight(color)) c.outline else Color.Transparent, CircleShape),
            )
        }
    }
}

/** Small uppercase-free section label used inside popovers and panels. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = Inkwell.colors.inkMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Labelled slider with its value shown on the right. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onChangeFinished: (() -> Unit)? = null,
) {
    val c = Inkwell.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.inkMuted, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = c.ink)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onChangeFinished,
            modifier = Modifier.height(36.dp),
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.surfaceAlt,
                activeTickColor = c.onAccent,
                inactiveTickColor = c.outline,
            ),
        )
    }
}

/** Small rounded chip-like toggle used for filters inside popovers. */
@Composable
fun ToggleChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Box(
        modifier
            .height(Dimens.minTouch)
            .combinedClickable(role = Role.Checkbox, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.accent else c.ink,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) c.accentContainer else Color.Transparent)
                .border(1.dp, if (selected) c.accent.copy(alpha = 0.4f) else c.outline, RoundedCornerShape(8.dp))
                .padding(PaddingValues(horizontal = 10.dp, vertical = 6.dp)),
        )
    }
}

/** A segmented control of text options (Ballpoint / Fountain / Brush, eraser modes, ...). */
@Composable
fun <T> Segmented(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, c.outline, RoundedCornerShape(8.dp))
            .background(c.surfaceAlt.copy(alpha = 0.5f))
            .padding(2.dp),
    ) {
        options.forEach { option ->
            val isSel = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isSel) c.surface else Color.Transparent)
                    .then(if (isSel) Modifier.border(1.dp, c.outline, RoundedCornerShape(6.dp)) else Modifier)
                    .combinedClickable(role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics { this.selected = isSel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSel) c.ink else c.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))
