package com.inkwell.notes.typed.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.ui.theme.Figtree
import com.inkwell.notes.ui.theme.Inkwell
import com.inkwell.notes.ui.theme.InkwellColors

/** Colours used to render inline formatting. */
@Immutable
data class SpanColors(val link: Color, val codeBackground: Color)

/** Builds the styled text of a block from its spans. */
fun styledText(text: String, spans: List<TextSpan>, colors: SpanColors): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)
    val b = AnnotatedString.Builder(text)
    for (s in spans) {
        val start = s.start.coerceIn(0, text.length)
        val end = s.end.coerceIn(start, text.length)
        if (start == end) continue
        val decorations = buildList {
            if (s.underline || s.link != null) add(TextDecoration.Underline)
            if (s.strike) add(TextDecoration.LineThrough)
        }
        b.addStyle(
            SpanStyle(
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
                fontFamily = if (s.code) FontFamily.Monospace else null,
                background = if (s.code) colors.codeBackground else Color.Unspecified,
                color = if (s.link != null) colors.link else Color.Unspecified,
                fontSize = if (s.code) 0.92.em else TextUnit.Unspecified,
            ),
            start, end,
        )
    }
    return b.toAnnotatedString()
}

/**
 * Renders a block's spans inside its text field without changing offsets. The field's own
 * value stays plain text; span offsets are maintained by the controller on every edit.
 */
data class SpanTransformation(val spans: List<TextSpan>, val colors: SpanColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styledText(text.text, spans, colors), OffsetMapping.Identity)
}

/** Typography of each block type. */
@Composable
fun blockTextStyle(block: TypedBlock): TextStyle {
    val c = Inkwell.colors
    return blockTextStyle(block, c)
}

fun blockTextStyle(block: TypedBlock, c: InkwellColors): TextStyle {
    val base = TextStyle(fontFamily = Figtree, fontSize = 16.sp, lineHeight = 26.sp, color = c.ink)
    return when (block.type) {
        BlockType.HEADING -> when (block.level) {
            1 -> base.copy(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
            2 -> base.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
            else -> base.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        BlockType.QUOTE -> base.copy(fontStyle = FontStyle.Italic, color = c.inkMuted)
        BlockType.CODE -> base.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 22.sp)
        BlockType.CHECKLIST -> if (block.checked) base.copy(color = c.inkMuted, textDecoration = TextDecoration.LineThrough) else base
        BlockType.IMAGE -> base.copy(fontSize = 13.sp, lineHeight = 18.sp, color = c.inkMuted)
        else -> base
    }
}
