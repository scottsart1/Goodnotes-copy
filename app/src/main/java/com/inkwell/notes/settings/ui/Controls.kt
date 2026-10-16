package com.inkwell.notes.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/** Title and optional subtitle used by every row. */
@Composable
internal fun RowLabel(title: String, subtitle: String?, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = Inkwell.colors
    Column(modifier.alpha(if (enabled) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = c.ink)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
    }
}

/** Title and subtitle with wrapping choice chips below. */
@Composable
internal fun <T> ChipsRow(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowLabel(title, subtitle, enabled)
        ChoiceChips(choices, selected, onSelect, enabled = enabled)
        footer?.invoke(this)
    }
}

/** A radio option with a description; the whole row selects. */
@Composable
internal fun RadioRow(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String? = null, enabled: Boolean = true) {
    val c = Inkwell.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.inkMuted),
        )
        Spacer(Modifier.width(12.dp))
        RowLabel(title, subtitle, enabled, Modifier.weight(1f))
    }
}

/** A setting showing its current value; tapping opens a menu of choices. */
@Composable
internal fun <T> DropdownRow(
    title: String,
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val c = Inkwell.colors
    var expanded by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.value == selected }?.label.orEmpty()
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = "Change $title") { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowLabel(title, subtitle, enabled, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(current, style = MaterialTheme.typography.labelLarge, color = if (enabled) c.ink else c.inkMuted, maxLines = 1)
            Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 4.dp).size(Dimens.icon), tint = c.inkMuted)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(c.surface),
            offset = DpOffset(16.dp, 0.dp),
        ) {
            choices.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.label, style = MaterialTheme.typography.bodyLarge, color = c.ink) },
                    onClick = {
                        expanded = false
                        if (choice.value != selected) onSelect(choice.value)
                    },
                    enabled = choice.enabled,
                    trailingIcon = if (choice.value == selected) {
                        { Icon(Icons.Outlined.Check, "Selected", Modifier.size(Dimens.icon), tint = c.accent) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = Dimens.minTouch),
                )
            }
        }
    }
}

/**
 * A slider that edits locally while dragging and commits once on release, so a drag is a
 * single settings write.
 */
@Composable
internal fun SliderRow(
    title: String,
    value: Float,
    onCommit: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: (Float) -> String,
    subtitle: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    snap: (Float) -> Float = { it },
    startLabel: String? = null,
    endLabel: String? = null,
) {
    val c = Inkwell.colors
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel(title, subtitle, enabled, Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(valueLabel(local), style = MaterialTheme.typography.labelLarge, color = if (enabled) c.ink else c.inkMuted)
        }
        InkSlider(
            value = local,
            onValueChange = { local = snap(it) },
            onValueChangeFinished = { if (local != value) onCommit(local) },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            stateLabel = valueLabel(local),
        )
        if (startLabel != null || endLabel != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(startLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.weight(1f))
                Text(endLabel.orEmpty(), style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
            }
        }
    }
}

@Composable
internal fun InkSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    stateLabel: String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val c = Inkwell.colors
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.surfaceAlt,
            activeTickColor = c.onAccent.copy(alpha = 0.5f),
            inactiveTickColor = c.outline,
        ),
        modifier = modifier.fillMaxWidth().semantics { stateDescription = stateLabel },
    )
}
