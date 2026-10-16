package com.inkwell.notes.editor.ui.selection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.editor.SelectionText
import com.inkwell.notes.editor.ui.components.LabeledSlider
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.roundToInt

/** Shows recognised handwriting for review before it replaces the ink. */
@Composable
fun ConvertTextDialog(result: SelectionText, onConvert: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(result) { mutableStateOf(result.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_convert_title)) },
        text = {
            Column(Modifier.widthIn(min = 280.dp, max = 520.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                val confidence = result.confidence
                val details = buildList {
                    if (confidence != null) add(stringResource(R.string.editor_convert_confidence, (confidence * 100).roundToInt()))
                    add(stringResource(R.string.editor_convert_on_device))
                }.joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = Inkwell.colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
                if (result.uncertain.isNotEmpty()) {
                    Text(
                        stringResource(R.string.editor_convert_uncertain, result.uncertain.take(6).joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = Inkwell.colors.warning,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConvert(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.editor_convert_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}

/** Crop insets for a single selected image, as fractions of its source. */
@Composable
fun CropDialog(onApply: (left: Float, top: Float, right: Float, bottom: Float) -> Unit, onDismiss: () -> Unit) {
    var l by remember { mutableFloatStateOf(0f) }
    var t by remember { mutableFloatStateOf(0f) }
    var r by remember { mutableFloatStateOf(0f) }
    var b by remember { mutableFloatStateOf(0f) }
    fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_crop_title)) },
        text = {
            Column(Modifier.widthIn(min = 280.dp, max = 420.dp)) {
                LabeledSlider(stringResource(R.string.editor_crop_left), l, 0f..0.45f, pct(l), { l = it })
                LabeledSlider(stringResource(R.string.editor_crop_top), t, 0f..0.45f, pct(t), { t = it })
                LabeledSlider(stringResource(R.string.editor_crop_right), r, 0f..0.45f, pct(r), { r = it })
                LabeledSlider(stringResource(R.string.editor_crop_bottom), b, 0f..0.45f, pct(b), { b = it })
            }
        },
        confirmButton = { TextButton(onClick = { onApply(l, t, r, b) }) { Text(stringResource(R.string.editor_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) } },
    )
}
