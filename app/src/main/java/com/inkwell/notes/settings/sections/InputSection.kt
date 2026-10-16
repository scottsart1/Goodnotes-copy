package com.inkwell.notes.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.FingerAction
import com.inkwell.notes.core.model.InputPrefs
import com.inkwell.notes.core.model.LongPressAction
import com.inkwell.notes.core.model.SideButtonMode
import com.inkwell.notes.core.model.TapGestureAction
import com.inkwell.notes.settings.logic.PressureCurveMath
import com.inkwell.notes.settings.pentest.PenTestPad
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.DropdownRow
import com.inkwell.notes.settings.ui.InkSlider
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.PressureCurveGraph
import com.inkwell.notes.settings.ui.PressureSampleStroke
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.RadioRow
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.RowLabel
import com.inkwell.notes.settings.ui.SegmentedRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SwitchRow
import com.inkwell.notes.ui.theme.Inkwell

/** The text the brief asks for, shown under "Hold to erase". */
internal const val HOLD_TO_ERASE_TEXT = "Hold the side button to erase with your current eraser; release to return to the same pen."

@Composable
internal fun InputSection(s: AppSettings, actions: SettingsActions) {
    val input = s.input
    fun set(transform: (InputPrefs) -> InputPrefs) = actions.update { it.copy(input = transform(it.input)) }

    SectionColumn {
        SettingsGroup("S Pen and fingers") {
            SwitchRow(
                "Only the S Pen writes",
                input.stylusOnlyInk,
                { v -> set { it.copy(stylusOnlyInk = v) } },
                subtitle = "Fingers scroll and zoom instead of drawing, so your hand can rest on the screen while you write.",
            )
            Note(
                "Palm rejection: Inkwell tells the S Pen tip apart from skin by the type of each touch, so only the pen inks. Touches as large as a palm are ignored too.",
                icon = Icons.Outlined.PanTool,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
            RowDivider()
            SegmentedRow(
                title = "One finger",
                subtitle = "What a single finger does on the page.",
                choices = listOf(
                    Choice(FingerAction.SCROLL, "Scroll"),
                    Choice(FingerAction.DRAW, "Draw", enabled = !input.stylusOnlyInk),
                    Choice(FingerAction.SELECT, "Select"),
                ),
                selected = input.fingerAction,
                onSelect = { a -> set { it.copy(fingerAction = a) } },
                footer = if (input.stylusOnlyInk) {
                    {
                        Note(
                            if (input.fingerAction == FingerAction.DRAW) "Fingers scroll while \"Only the S Pen writes\" is on. Turn it off to draw with a finger."
                            else "To draw with a finger, turn off \"Only the S Pen writes\".",
                        )
                    }
                } else {
                    null
                },
            )
        }

        SettingsGroup("S Pen side button", description = "Choose what holding or pressing the button on the side of the S Pen does.") {
            RadioRow(
                "Hold to erase",
                input.sideButton == SideButtonMode.HOLD_TO_ERASE,
                { set { it.copy(sideButton = SideButtonMode.HOLD_TO_ERASE) } },
                subtitle = "$HOLD_TO_ERASE_TEXT Recommended.",
            )
            RowDivider()
            RadioRow(
                "Toggle eraser",
                input.sideButton == SideButtonMode.TOGGLE_ERASER,
                { set { it.copy(sideButton = SideButtonMode.TOGGLE_ERASER) } },
                subtitle = "Press once to switch to the eraser, press again to go back to your pen.",
            )
            RowDivider()
            RadioRow(
                "Hold to lasso",
                input.sideButton == SideButtonMode.HOLD_TO_LASSO,
                { set { it.copy(sideButton = SideButtonMode.HOLD_TO_LASSO) } },
                subtitle = "Hold the side button to select with the lasso; release to return to the same pen.",
            )
            RowDivider()
            RadioRow(
                "Off",
                input.sideButton == SideButtonMode.OFF,
                { set { it.copy(sideButton = SideButtonMode.OFF) } },
                subtitle = "The side button does nothing in Inkwell.",
            )
        }

        SettingsGroup("S Pen test pad", description = "Check that pressure, tilt, hover and the side button reach Inkwell.") {
            PenTestPad()
        }

        SettingsGroup("Gestures") {
            DropdownRow(
                "Long press",
                listOf(
                    Choice(LongPressAction.CONTEXT_MENU, "Show the menu"),
                    Choice(LongPressAction.LASSO, "Start a lasso"),
                    Choice(LongPressAction.PASTE_MENU, "Paste options"),
                    Choice(LongPressAction.NONE, "Nothing"),
                ),
                input.longPress,
                { a -> set { it.copy(longPress = a) } },
                subtitle = "Touch and hold on the page.",
            )
            RowDivider()
            DropdownRow(
                "Two-finger tap",
                tapChoices(),
                input.twoFingerTap,
                { a -> set { it.copy(twoFingerTap = a) } },
            )
            RowDivider()
            DropdownRow(
                "Three-finger tap",
                tapChoices(),
                input.threeFingerTap,
                { a -> set { it.copy(threeFingerTap = a) } },
            )
            RowDivider()
            SwitchRow(
                "Scribble to erase",
                input.scribbleToErase,
                { v -> set { it.copy(scribbleToErase = v) } },
                subtitle = "Scribble back and forth over ink to erase it.",
            )
            RowDivider()
            SwitchRow(
                "Circle to select",
                input.circleToSelect,
                { v -> set { it.copy(circleToSelect = v) } },
                subtitle = "Draw a loop around ink and hold still to select it.",
            )
            RowDivider()
            SwitchRow(
                "Draw and hold to straighten",
                input.holdToStraighten,
                { v -> set { it.copy(holdToStraighten = v) } },
                subtitle = "Pause at the end of a stroke to turn it into a straight line or a clean shape.",
            )
            RowDivider()
            SwitchRow(
                "Automatic shape recognition",
                input.autoShapeRecognition,
                { v -> set { it.copy(autoShapeRecognition = v) } },
                subtitle = "Closed strokes become clean circles, rectangles and triangles without holding.",
            )
            RowDivider()
            SwitchRow(
                "Zoom while writing",
                input.zoomWhileWriting,
                { v -> set { it.copy(zoomWhileWriting = v) } },
                subtitle = "Pinch with two fingers even while the pen is near the screen.",
            )
            RowDivider()
            SwitchRow(
                "Ignore palm-sized touches",
                input.rejectLargeContacts,
                { v -> set { it.copy(rejectLargeContacts = v) } },
                subtitle = "Large contact areas never ink or scroll, even when fingers can draw.",
            )
        }

        SettingsGroup("Pressure") {
            PressureCurveRow(input.pressureCurve) { v -> set { it.copy(pressureCurve = v) } }
        }

        SettingsGroup("Responsiveness") {
            SwitchRow(
                "Low-latency ink",
                input.lowLatencyInk,
                { v -> set { it.copy(lowLatencyInk = v) } },
                subtitle = "Draws strokes straight to the display for the fastest response. Turn off if you see flicker.",
            )
            RowDivider()
            SwitchRow(
                "Motion prediction",
                input.motionPrediction,
                { v -> set { it.copy(motionPrediction = v) } },
                subtitle = "Predicts the next bit of the stroke to hide the remaining lag. Turn off if you see flicker or overshoot.",
            )
            RowDivider()
            SwitchRow(
                "Hover cursor",
                input.showHoverCursor,
                { v -> set { it.copy(showHoverCursor = v) } },
                subtitle = "Shows where the pen tip will land while it hovers above the page.",
            )
        }
    }
}

private fun tapChoices() = listOf(
    Choice(TapGestureAction.UNDO, "Undo"),
    Choice(TapGestureAction.REDO, "Redo"),
    Choice(TapGestureAction.TOGGLE_ERASER, "Switch to eraser"),
    Choice(TapGestureAction.NONE, "Nothing"),
)

@Composable
private fun PressureCurveRow(value: Float, onCommit: (Float) -> Unit) {
    val c = Inkwell.colors
    var local by remember(value) { mutableFloatStateOf(PressureCurveMath.snap(value)) }
    val commit = { v: Float -> if (v != value) onCommit(v) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RowLabel("Pressure curve", PressureCurveMath.describe(local), enabled = true)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 480.dp
            val controls: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PressureSampleStroke(local, Modifier.fillMaxWidth().height(72.dp))
                    InkSlider(
                        value = local,
                        onValueChange = { local = PressureCurveMath.snap(it) },
                        onValueChangeFinished = { commit(local) },
                        valueRange = 0f..1f,
                        stateLabel = when (PressureCurveMath.feel(local)) {
                            PressureCurveMath.Feel.FIRM -> "Firm"
                            PressureCurveMath.Feel.LINEAR -> "Linear"
                            PressureCurveMath.Feel.SOFT -> "Soft"
                        },
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Firm", style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.weight(1f))
                        Text("Linear", style = MaterialTheme.typography.labelSmall, color = c.inkMuted)
                        Text("Soft", style = MaterialTheme.typography.labelSmall, color = c.inkMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                    if (local != PressureCurveMath.LINEAR) {
                        Row {
                            Spacer(Modifier.weight(1f))
                            QuietButton("Reset to linear", onClick = { local = PressureCurveMath.LINEAR; commit(PressureCurveMath.LINEAR) })
                        }
                    }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                    PressureCurveGraph(local, Modifier.width(168.dp).aspectRatio(1f))
                    controls(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PressureCurveGraph(local, Modifier.width(168.dp).aspectRatio(1f))
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
