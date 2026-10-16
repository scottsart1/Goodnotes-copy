package com.inkwell.notes.editor.ui.toolbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.editor.logic.ColorMath
import com.inkwell.notes.editor.ui.components.HSpace
import com.inkwell.notes.editor.ui.components.SectionLabel
import com.inkwell.notes.ui.theme.Inkwell

/** HSV colour picker with a hex field. Tracks are drawn as discrete steps, not gradients. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember { ColorMath.argbToHsv(initial) }
    var h by remember { mutableFloatStateOf(start[0]) }
    var s by remember { mutableFloatStateOf(start[1]) }
    var v by remember { mutableFloatStateOf(start[2]) }
    var hex by remember { mutableStateOf(ColorMath.toHex(initial)) }
    val color = ColorMath.hsvToArgb(h, s, v)

    fun syncHex() { hex = ColorMath.toHex(ColorMath.hsvToArgb(h, s, v)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_custom_color)) },
        text = {
            Column(Modifier.width(320.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(color))
                            .border(1.dp, Inkwell.colors.outline, RoundedCornerShape(12.dp)),
                    )
                    HSpace(12.dp)
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text.take(9)
                            ColorMath.parseHex(text)?.let { parsed ->
                                val hsv = ColorMath.argbToHsv(parsed)
                                h = hsv[0]; s = hsv[1]; v = hsv[2]
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(R.string.editor_hex)) },
                        isError = ColorMath.parseHex(hex) == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        modifier = Modifier.weight(1f),
                    )
                }
                SectionLabel(stringResource(R.string.editor_hue))
                StepTrack(value = h / 360f, steps = 36, colorAt = { f -> ColorMath.hsvToArgb(f * 360f, 1f, 1f) }, description = stringResource(R.string.editor_hue)) {
                    h = it * 360f; syncHex()
                }
                SectionLabel(stringResource(R.string.editor_saturation))
                StepTrack(value = s, steps = 20, colorAt = { f -> ColorMath.hsvToArgb(h, f, v.coerceAtLeast(0.2f)) }, description = stringResource(R.string.editor_saturation)) {
                    s = it; syncHex()
                }
                SectionLabel(stringResource(R.string.editor_brightness))
                StepTrack(value = v, steps = 20, colorAt = { f -> ColorMath.hsvToArgb(h, s, f) }, description = stringResource(R.string.editor_brightness)) {
                    v = it; syncHex()
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.editor_use_color)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** A horizontal track of [steps] colour blocks with a draggable marker; reports 0..1. */
@Composable
private fun StepTrack(value: Float, steps: Int, colorAt: (Float) -> Int, description: String, onChange: (Float) -> Unit) {
    val outline = Inkwell.colors.outline
    val marker = Inkwell.colors.ink
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { onChange((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val w = size.width / steps
        for (i in 0 until steps) {
            drawRect(Color(colorAt((i + 0.5f) / steps)), topLeft = Offset(i * w, 0f), size = Size(w + 0.5f, size.height))
        }
        drawRoundRect(outline, size = size, cornerRadius = CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
        val x = value.coerceIn(0f, 1f) * size.width
        drawRoundRect(marker, topLeft = Offset(x - 3.dp.toPx(), -2.dp.toPx()), size = Size(6.dp.toPx(), size.height + 4.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(2.dp.toPx()))
    }
}
