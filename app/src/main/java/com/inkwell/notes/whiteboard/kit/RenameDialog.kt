package com.inkwell.notes.whiteboard.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.inkwell.notes.R
import com.inkwell.notes.ui.theme.Inkwell
import kotlinx.coroutines.launch

/**
 * Manual rename. [onRename] returns false when saving failed; the dialog then stays open
 * with an error. A manual rename stops automatic "YYYYMMDD_Topic" naming for the document.
 */
@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: suspend (String) -> Boolean) {
    val c = Inkwell.colors
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        val title = value.text.trim()
        if (title.isEmpty()) { error = R.string.cd_title_empty; return }
        if (title == initial) { onDismiss(); return }
        saving = true
        scope.launch {
            val ok = onRename(title)
            saving = false
            if (ok) onDismiss() else error = R.string.cd_rename_failed
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        titleContentColor = c.ink,
        textContentColor = c.ink,
        title = { Text(stringResource(R.string.cd_rename_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text(stringResource(R.string.cd_title_label)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { e -> { Text(stringResource(e)) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = c.accent, unfocusedBorderColor = c.outline, cursorColor = c.accent,
                        focusedLabelColor = c.accent, unfocusedLabelColor = c.inkMuted,
                        focusedTextColor = c.ink, unfocusedTextColor = c.ink,
                        errorBorderColor = c.danger, errorLabelColor = c.danger, errorSupportingTextColor = c.danger,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = !saving) { Text(stringResource(R.string.cd_save), color = c.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cd_cancel), color = c.inkMuted) }
        },
    )
}
