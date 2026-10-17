package com.inkwell.notes.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.inkwell.notes.R
import com.inkwell.notes.app.SettingsSection
import com.inkwell.notes.settings.SettingsCatalog
import com.inkwell.notes.settings.ui.ActionRow
import com.inkwell.notes.core.model.AiTask
import com.inkwell.notes.core.model.AppSettings
import com.inkwell.notes.core.model.DockSide
import com.inkwell.notes.core.model.RecordingPrefs
import com.inkwell.notes.core.model.SpeakerEngine
import com.inkwell.notes.settings.logic.SettingsFormat
import com.inkwell.notes.settings.ui.Choice
import com.inkwell.notes.settings.ui.Note
import com.inkwell.notes.settings.ui.NoteTone
import com.inkwell.notes.settings.ui.QuietButton
import com.inkwell.notes.settings.ui.RadioRow
import com.inkwell.notes.settings.ui.RowDivider
import com.inkwell.notes.settings.ui.SegmentedRow
import com.inkwell.notes.settings.ui.SettingsActions
import com.inkwell.notes.settings.ui.SettingsGroup
import com.inkwell.notes.settings.ui.SwitchRow

internal val SegmentChoices = listOf(60, 120, 300)
private val QualityChoices = listOf(64_000 to "Standard", 96_000 to "High", 128_000 to "Max")

@Composable
internal fun RecordingSection(s: AppSettings, actions: SettingsActions, openSection: (SettingsSection) -> Unit) {
    val rec = s.recording
    val transcription by rememberAiTaskStatus(actions.graph, AiTask.TRANSCRIPTION, s.ai)
    fun set(transform: (RecordingPrefs) -> RecordingPrefs) = actions.update { it.copy(recording = transform(it.recording)) }

    SectionColumn {
        SettingsGroup("Recorder") {
            SegmentedRow(
                title = "Recorder position",
                subtitle = "The recording control sits at this edge of the editor and collapses into a slim sliver.",
                choices = listOf(Choice(DockSide.LEFT, "Left edge"), Choice(DockSide.RIGHT, "Right edge")),
                selected = rec.dockSide,
                onSelect = { d -> set { it.copy(dockSide = d) } },
            )
            RowDivider()
            SwitchRow(
                "Start collapsed",
                rec.startCollapsed,
                { v -> set { it.copy(startCollapsed = v) } },
                subtitle = "New recordings start as the slim sliver with a red dot. Tap it to expand the controls.",
            )
            RowDivider()
            SwitchRow(
                "Highlight notes during playback",
                rec.highlightDuringPlayback,
                { v -> set { it.copy(highlightDuringPlayback = v) } },
                subtitle = "While audio plays, the ink you wrote at that moment is highlighted.",
            )
        }

        SettingsGroup("Transcription") {
            SwitchRow(
                "Transcribe after recording",
                rec.autoTranscribe,
                { v -> set { it.copy(autoTranscribe = v) } },
                subtitle = "When you stop, the audio is sent to OpenAI for a searchable transcript. Needs an OpenAI key.",
            )
            val status = transcription
            if (rec.autoTranscribe && status != null && !status.available) {
                Note(
                    "No transcription model is set up yet, so recordings won't be transcribed. Add an OpenAI key in AI providers.",
                    tone = NoteTone.WARNING,
                    icon = Icons.Outlined.WarningAmber,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    QuietButton("Open AI providers", onClick = { openSection(SettingsSection.AI) })
                }
            } else if (rec.autoTranscribe && status?.available == true) {
                Note(
                    "Transcribing with ${status.modelName ?: "your transcription model"}.",
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            if (rec.autoTranscribe && s.speakers.engine == SpeakerEngine.OPENAI) {
                Note(stringResource(R.string.spk_consent_clips), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            }
        }

        SettingsGroup("Audio", description = "Higher quality sounds clearer and takes more space.") {
            QualityChoices.forEach { (bitrate, label) ->
                RadioRow(
                    title = label,
                    selected = rec.bitrate == bitrate,
                    onClick = { set { it.copy(bitrate = bitrate) } },
                    subtitle = SettingsFormat.audioQualityLabel(bitrate),
                )
                RowDivider()
            }
            SegmentedRow(
                title = "Crash-safe segments",
                subtitle = "Audio is saved in short segments while you record, so a crash or a flat battery loses at most the last one.",
                choices = SegmentChoices.map { Choice(it, SettingsFormat.minutesLabel(it)) },
                selected = rec.segmentSeconds,
                onSelect = { sec -> set { it.copy(segmentSeconds = sec) } },
            )
        }

        SettingsGroup("Speakers") {
            ActionRow(
                title = SettingsSection.SPEAKERS.title,
                subtitle = SettingsCatalog.SPEAKERS_SUMMARY,
                actionLabel = "Open",
                onAction = { openSection(SettingsSection.SPEAKERS) },
            )
        }

        Note("Changes apply to the next recording. A recording in progress keeps its settings.")
    }
}
