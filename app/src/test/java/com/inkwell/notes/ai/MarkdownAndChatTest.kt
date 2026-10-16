package com.inkwell.notes.ai

import com.inkwell.notes.ai.chat.ChatCodec
import com.inkwell.notes.ai.context.SourceRef
import com.inkwell.notes.ai.ui.Markdown
import com.inkwell.notes.ai.ui.Markdown.Block
import com.inkwell.notes.core.model.AiProviderId
import com.inkwell.notes.core.model.AiRole
import com.inkwell.notes.core.model.ModelRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownAndChatTest {
    @Test fun parsesBlocks() {
        val md = """
            # Summary

            The **key** idea is *inflation* [p.3].
            Second line.

            - one
            - two
              continued
            1. first
            2) second

            ```kotlin
            val x = 1
            ```

            $$
            E = mc^2
            $$

            > quoted

            | a | b |
            |---|---|
            | 1 | 2 |

            ---
        """.trimIndent()
        val blocks = Markdown.parse(md)
        assertEquals(Block.Heading(1, "Summary"), blocks[0])
        assertEquals(Block.Paragraph("The **key** idea is *inflation* [p.3].\nSecond line."), blocks[1])
        assertEquals(Block.Bullets(listOf(Markdown.Item("one"), Markdown.Item("two\ncontinued"))), blocks[2])
        assertEquals(listOf(1, 2), (blocks[3] as Block.Numbered).items.map { it.number })
        assertEquals(Block.Code("kotlin", "val x = 1"), blocks[4])
        assertEquals(Block.Math("E = mc^2"), blocks[5])
        assertEquals(Block.Quote("quoted"), blocks[6])
        assertEquals(Block.Table(listOf(listOf("a", "b"), listOf("1", "2"))), blocks[7])
        assertEquals(Block.Rule, blocks[8])
    }

    @Test fun inlineSpans() {
        val spans = Markdown.inline("A **bold** and *it* with `code`, ${'$'}x^2${'$'} and [p.3] plus [link](https://x.y) for ${'$'}5 or ${'$'}10.")
        assertTrue(spans.any { it.bold && it.text == "bold" })
        assertTrue(spans.any { it.italic && it.text == "it" })
        assertTrue(spans.any { it.code && it.text == "code" })
        assertTrue(spans.any { it.math && it.text == "x^2" })
        assertEquals("p.3", spans.single { it.citation != null }.citation)
        val plain = spans.joinToString("") { it.text }
        assertTrue(plain.contains("link for \$5 or \$10."))
        assertTrue("snake_case stays literal", Markdown.inline("file_name_here").single().text == "file_name_here")
        assertTrue("[illegible] is not a citation", Markdown.inline("a [illegible] b").none { it.citation != null })
    }

    @Test fun plainTextForInsertion() {
        val text = Markdown.toPlainText("## Plan\n\n- **Buy** milk [p.2]\n- Call *Sam*\n\n1. First\n\n${'$'}${'$'}a+b${'$'}${'$'}")
        assertEquals("Plan\n\n• Buy milk [p.2]\n• Call Sam\n\n1. First\n\n${'$'}${'$'}a+b${'$'}${'$'}", text)
    }

    @Test fun chatCodecRoundTrip() {
        val ref = ModelRef(AiProviderId.ANTHROPIC, "claude-opus-5-5")
        assertEquals(ref, ChatCodec.parseModel(ref.toString()))
        assertEquals(ModelRef(AiProviderId.OPENAI, "ft:gpt-4o:org:custom"), ChatCodec.parseModel("OPENAI:ft:gpt-4o:org:custom"))
        assertNull(ChatCodec.parseModel("NOPE:x"))
        assertNull(ChatCodec.parseModel(null))
        val sources = listOf(SourceRef("p.3", null, "d1", "Title", 2), SourceRef("D2 p.1", "D2", "d2", "Other", 0), SourceRef("rec 1:00", null, "d1", null, null, "r1", 60_000))
        assertEquals(sources, ChatCodec.decodeSources(ChatCodec.encodeSources(sources)))
        assertNull(ChatCodec.encodeSources(emptyList()))
        assertEquals(emptyList<SourceRef>(), ChatCodec.decodeSources("not json"))
        assertEquals(AiRole.ASSISTANT, ChatCodec.role("assistant"))
        assertEquals("user", ChatCodec.roleName(AiRole.USER))
    }
}
