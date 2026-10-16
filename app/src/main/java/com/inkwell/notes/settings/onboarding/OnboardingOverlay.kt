package com.inkwell.notes.settings.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.inkwell.notes.ai.ui.AiSettingsContent
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.naming.DateNaming
import com.inkwell.notes.settings.sections.SectionHost
import com.inkwell.notes.settings.ui.PrimaryButton
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.Radius16
import com.inkwell.notes.settings.ui.Radius8
import com.inkwell.notes.settings.ui.SettingsIconButton
import com.inkwell.notes.ui.theme.Inkwell
import kotlin.math.PI
import kotlin.math.sin

private const val STEPS = 3

/**
 * First-run introduction in three compact steps, shown over the app until finished or skipped.
 * [onFinish] marks onboarding done.
 */
@Composable
internal fun OnboardingOverlay(delayHours: Int, onFinish: () -> Unit) {
    val c = Inkwell.colors
    var step by rememberSaveable { mutableStateOf(0) }
    var showAiSetup by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (step > 0) step-- else onFinish() }

    Box(
        Modifier
            .fillMaxSize()
            .background(c.desk.copy(alpha = 0.88f))
            // Swallow touches so nothing underneath reacts while the introduction is up.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            shape = Radius16,
            color = c.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.outline),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                StepDots(step)
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "onboarding-step",
                ) { s ->
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            Modifier.fillMaxWidth().height(132.dp).background(c.surfaceAlt, Radius8),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (s) {
                                0 -> PenIllustration()
                                1 -> NamingIllustration()
                                else -> KeyIllustration()
                            }
                        }
                        Text(stepTitle(s), style = MaterialTheme.typography.headlineSmall, color = c.ink, modifier = Modifier.semantics { heading() })
                        Text(stepBody(s, delayHours), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (step < STEPS - 1) {
                        QuietButton("Skip", onClick = onFinish, color = c.inkMuted)
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("Next", onClick = { step++ })
                    } else {
                        QuietButton("Set up later", onClick = onFinish, color = c.inkMuted)
                        Spacer(Modifier.weight(1f))
                        PrimaryButton("Add a key", onClick = { showAiSetup = true })
                    }
                }
            }
        }
    }

    if (showAiSetup) {
        AiSetupDialog(onDone = {
            showAiSetup = false
            onFinish()
        })
    }
}

private fun stepTitle(step: Int): String = when (step) {
    0 -> "Write with your S Pen"
    1 -> "Your files name themselves"
    else -> "Bring your own AI key (optional)"
}

private fun stepBody(step: Int, delayHours: Int): String = when (step) {
    0 -> "Only the pen writes, so your hand can rest on the screen. Hold the side button to erase; let go and you're back to the same pen."
    1 -> "New files are named YYYYMMDD_Topic. The date never changes, and about $delayHours hours after you start, the topic is filled in from your notes. Rename a file yourself any time."
    else -> "Inkwell works fully offline. Add an OpenAI, Anthropic, DeepSeek or Moonshot key to ask about your notes, summarise PDFs and transcribe recordings. Keys stay encrypted on this device."
}

@Composable
private fun StepDots(step: Int) {
    val c = Inkwell.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.semantics { contentDescription = "Step ${step + 1} of $STEPS" },
    ) {
        repeat(STEPS) { i ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (i == step) 24.dp else 12.dp)
                    .background(if (i == step) c.accent else c.outline, Radius8),
            )
        }
    }
}

@Composable
private fun PenIllustration() {
    val c = Inkwell.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.width(220.dp).height(44.dp)) {
            val path = Path()
            val steps = 60
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val x = size.width * t
                val y = size.height / 2f + size.height * 0.3f * sin(t * 5.0 * PI).toFloat() * (1f - 0.4f * t)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, c.ink, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            drawCircle(c.accent, 3.dp.toPx(), Offset(size.width, size.height / 2f))
        }
        Text(
            "Side button held · Eraser",
            style = MaterialTheme.typography.labelMedium,
            color = c.surface,
            modifier = Modifier.background(c.ink, Radius16).padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NamingIllustration() {
    val c = Inkwell.colors
    val now = remember { System.currentTimeMillis() }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 12.dp),
    ) {
        Text(
            DateNaming.initialTitle(now),
            style = MaterialTheme.typography.labelLarge,
            color = c.inkMuted,
            modifier = Modifier.background(c.surface, Radius8).border(1.dp, c.outline, Radius8).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp), tint = c.inkMuted)
        Text(
            DateNaming.titleFor(now, "Monetary Policy"),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink,
            modifier = Modifier.background(c.accentContainer, Radius8).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyIllustration() {
    val c = Inkwell.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Key, null, Modifier.size(28.dp), tint = c.ink)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            AiProviderId.entries.forEach { p ->
                Text(
                    p.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.inkMuted,
                    modifier = Modifier.background(c.surface, Radius8).border(1.dp, c.outline, Radius8).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** The AI providers pane in a large dialog, so a key can be added straight from onboarding. */
@Composable
private fun AiSetupDialog(onDone: () -> Unit) {
    val c = Inkwell.colors
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .widthIn(max = 880.dp)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f),
            shape = Radius16,
            color = c.ground,
            border = androidx.compose.foundation.BorderStroke(1.dp, c.outline),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("AI providers", style = MaterialTheme.typography.titleLarge, color = c.ink, modifier = Modifier.weight(1f).semantics { heading() })
                    QuietButton("Done", onClick = onDone)
                    SettingsIconButton(Icons.Outlined.Close, "Close", onDone)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.outline))
                SectionHost {
                    AiSettingsContent(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 16.dp))
                }
            }
        }
    }
}
