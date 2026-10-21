package com.inkwell.notes.export.sync.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkwell.notes.R
import com.inkwell.notes.core.model.DestinationPrefs
import com.inkwell.notes.export.sync.VaultUrl
import com.inkwell.notes.export.sync.hasAnyTarget
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.core.model.OutputTarget

@Composable
internal fun FolderCard(
    prefs: DestinationPrefs,
    check: CheckState,
    onChoose: () -> Unit,
    onTest: () -> Unit,
) {
    val c = Inkwell.colors
    val chosen = prefs.folderUri != null
    StorageCard(title = stringResource(R.string.sync_folder_title), subtitle = stringResource(R.string.sync_folder_subtitle), icon = Icons.Outlined.Folder) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.widthIn(max = 360.dp).heightIn(min = Dimens.minTouch), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(Dimens.icon), tint = if (chosen) c.accent else c.inkMuted)
                Spacer(Modifier.width(10.dp))
                Text(
                    prefs.folderLabel?.takeIf { chosen } ?: stringResource(R.string.sync_folder_none),
                    style = MaterialTheme.typography.bodyLarge, color = if (chosen) c.ink else c.inkMuted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            SecondaryAction(stringResource(if (chosen) R.string.sync_folder_change else R.string.sync_folder_choose), onChoose)
            if (chosen) SecondaryAction(
                stringResource(if (check == CheckState.Running) R.string.sync_testing else R.string.sync_folder_test),
                onTest, busy = check == CheckState.Running,
            )
        }
        CheckResult(check)
        Text(stringResource(R.string.sync_folder_layout), style = MaterialTheme.typography.bodySmall, color = c.inkMuted)
    }
}

@Composable
internal fun VaultCard(
    prefs: DestinationPrefs,
    hasPassword: Boolean,
    check: CheckState,
    onUrlChanged: (String) -> Unit,
    onUrlCommit: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onPasswordCommit: () -> Unit,
    onForgetPassword: () -> Unit,
    onTest: (url: String, password: String) -> Unit,
    onBrowserError: () -> Unit,
) {
    val c = Inkwell.colors
    val uriHandler = LocalUriHandler.current
    var url by rememberSaveable { mutableStateOf(prefs.vaultUrl) }
    // Inkwell itself can update the saved link (the vault moved between www.host and host):
    // show the new one, unless the field holds something else the user typed.
    var lastSaved by rememberSaveable { mutableStateOf(prefs.vaultUrl) }
    LaunchedEffect(prefs.vaultUrl) {
        if (prefs.vaultUrl != lastSaved && VaultUrl.normalize(url) == VaultUrl.normalize(lastSaved)) url = prefs.vaultUrl
        lastSaved = prefs.vaultUrl
    }
    // The password is never read back from storage; this field only holds what is typed now.
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    var urlFocused by remember { mutableStateOf(false) }
    val parsed = remember(url) { VaultUrl.parse(url) }
    // Explain a malformed link once the user leaves the field, not on every keystroke.
    val urlError = (parsed as? VaultUrl.Parsed.Invalid)?.reason?.takeIf { !urlFocused }
    val normalized = (parsed as? VaultUrl.Parsed.Valid)?.endpoint?.baseUrl

    StorageCard(title = stringResource(R.string.sync_vault_title), subtitle = stringResource(R.string.sync_vault_subtitle), icon = Icons.Outlined.CloudUpload) {
        LabeledField(stringResource(R.string.sync_vault_url)) {
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    onUrlChanged(it)
                    // A password still in the field is for the link the field now shows: save it
                    // again for that link (one saved for another link only ever goes to that link).
                    if (password.isNotEmpty()) onPasswordChanged(password)
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (urlFocused && !it.isFocused) onUrlCommit(url)
                    urlFocused = it.isFocused
                },
                placeholder = { Text(VaultUrl.PLACEHOLDER, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                shape = R12,
                colors = inkFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
            )
        }
        LabeledField(stringResource(R.string.sync_vault_password)) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; onPasswordChanged(it) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onPasswordCommit() },
                placeholder = {
                    Text(
                        stringResource(if (hasPassword) R.string.sync_vault_password_saved else R.string.sync_vault_password_hint),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = if (hasPassword && password.isEmpty()) ({ Icon(Icons.Outlined.Lock, null, Modifier.size(Dimens.icon)) }) else null,
                singleLine = true,
                shape = R12,
                colors = inkFieldColors(),
                visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onPasswordCommit() }),
                trailingIcon = {
                    SmallIconButton(
                        if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        stringResource(if (reveal) R.string.sync_vault_password_hide else R.string.sync_vault_password_show),
                        { reveal = !reveal },
                    )
                },
            )
        }
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            SecondaryAction(
                stringResource(if (check == CheckState.Running) R.string.sync_testing else R.string.sync_vault_test),
                { onTest(url, password) },
                enabled = normalized != null,
                busy = check == CheckState.Running,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                QuietAction(stringResource(R.string.sync_vault_open), {
                    val target = normalized ?: return@QuietAction
                    try {
                        uriHandler.openUri(target)
                    } catch (e: Exception) {
                        onBrowserError()
                    }
                }, enabled = normalized != null)
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp), tint = if (normalized != null) c.accent else c.inkMuted)
            }
            if (hasPassword) QuietAction(stringResource(R.string.sync_vault_forget), { password = ""; onForgetPassword() }, color = c.danger)
        }
        CheckResult(check)
        val usesVault = prefs.hasAnyTarget && (OutputTarget.VAULT in prefs.notes || OutputTarget.VAULT in prefs.transcripts || OutputTarget.VAULT in prefs.audio)
        if (usesVault && normalized == null) {
            InfoLine(stringResource(R.string.sync_vault_needs_setup))
        }
    }
}

/** Small label above a field, so the placeholder stays visible while the field is empty. */
@Composable
private fun LabeledField(label: String, field: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Inkwell.colors.inkMuted)
        field()
    }
}

@Composable
internal fun InfoLine(text: String) {
    val c = Inkwell.colors
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = c.warning)
        Spacer(Modifier.width(8.dp))
        Column { Text(text, style = MaterialTheme.typography.bodySmall, color = c.ink) }
    }
}
