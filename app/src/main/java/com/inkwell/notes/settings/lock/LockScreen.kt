package com.inkwell.notes.settings.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.Radius8
import com.inkwell.notes.ui.theme.Dimens
import com.inkwell.notes.ui.theme.Inkwell

/**
 * Full-screen lock: wordmark and Unlock. Prompts by itself when the app locked on its own
 * (cold start, return from background) and the window is resumed; otherwise waits for Unlock.
 */
@Composable
internal fun LockScreen(onUnlocked: () -> Unit) {
    val c = Inkwell.colors
    val context = LocalContext.current
    val activity = remember(context) { Biometrics.findActivity(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val promptRequest by AppLockManager.promptRequests.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    var notSetUp by remember { mutableStateOf(false) }
    var prompting by remember { mutableStateOf(false) }

    fun prompt() {
        if (prompting) return
        val act = activity
        if (act == null) {
            message = "Couldn't show the unlock prompt. Close and reopen Inkwell."
            return
        }
        when (val availability = Biometrics.availability(context)) {
            AuthAvailability.NOT_SET_UP, AuthAvailability.UNSUPPORTED -> {
                notSetUp = true
                message = AuthErrors.availabilityMessage(availability)
                return
            }
            else -> notSetUp = false
        }
        prompting = true
        Biometrics.authenticate(
            activity = act,
            title = "Unlock Inkwell",
            subtitle = null,
            onSuccess = {
                prompting = false
                message = null
                onUnlocked()
            },
            onFailure = { failure ->
                prompting = false
                when (failure.kind) {
                    AuthFailureKind.CANCELLED -> message = null
                    AuthFailureKind.NOT_SET_UP -> {
                        notSetUp = true
                        message = failure.message
                    }
                    AuthFailureKind.RETRY -> message = failure.message
                }
            },
        )
    }

    LaunchedEffect(promptRequest) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (AppLockManager.consumeAutoPrompt()) prompt()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(64.dp).background(c.surface, CircleShape).border(1.dp, c.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(28.dp), tint = c.ink)
            }
            Text("Inkwell", style = MaterialTheme.typography.displaySmall, color = c.ink, modifier = Modifier.semantics { heading() })
            Text(
                "Your notes are locked.",
                style = MaterialTheme.typography.bodyLarge,
                color = c.inkMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            if (!notSetUp) {
                Button(
                    onClick = { prompt() },
                    enabled = !prompting,
                    shape = Radius8,
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 180.dp),
                ) {
                    Icon(Icons.Outlined.Fingerprint, null, Modifier.size(Dimens.icon))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock", style = MaterialTheme.typography.labelLarge)
                }
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (notSetUp) c.warning else c.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (notSetUp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    QuietButton("Open Android settings", onClick = {
                        if (!Biometrics.openSecuritySettings(context)) message = "Couldn't open Android settings."
                    })
                    // Without any screen lock the device itself is unprotected; the app lock
                    // cannot verify anyone, so it must not lock the owner out of their notes.
                    QuietButton("Continue", onClick = onUnlocked, color = c.inkMuted)
                }
                Text(
                    "Inkwell can't verify you until this device has a screen lock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
