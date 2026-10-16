package com.inkwell.notes.typed

import com.inkwell.notes.core.model.BlockType
import com.inkwell.notes.core.model.TextSpan
import com.inkwell.notes.core.model.TypedBlock
import com.inkwell.notes.core.model.TypedDocument
import com.inkwell.notes.typed.model.TableOps
import com.inkwell.notes.typed.model.TypedCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TypedDocumentDataTest {

    private val sample = TypedDocument(
        version = 1,
        blocks = listOf(
            TypedBlock("h", BlockType.HEADING, "Weekly review", level = 1),
            TypedBlock("p", BlockType.PARAGRAPH, "Some bold and linked text", listOf(TextSpan(5, 9, bold = true), TextSpan(14, 20, link = "https://example.com", underline = true))),
            TypedBlock("b", BlockType.BULLET, "nested", level = 2),
            TypedBlock("c", BlockType.CHECKLIST, "done", checked = true),
            TypedBlock("q", BlockType.QUOTE, "quote"),
            TypedBlock("code", BlockType.CODE, "fun main() {\n}", language = "kotlin"),
            TypedBlock("t", BlockType.TABLE, rows = listOf(listOf("A", "B"), listOf("1", "2"))),
            TypedBlock("i", BlockType.IMAGE, "A caption", assetId = "img-1.png"),
            TypedBlock("d", BlockType.DIVIDER),
        ),
    )

    @Test fun jsonRoundTripPreservesEverything() {
        val json = TypedCodec.encode(sample)
        assertEquals(sample, TypedCodec.decode(json))
    }

    @Test fun defaultsAreNotWrittenAndUnknownKeysAreIgnored() {
        val json = TypedCodec.encode(TypedDocument(1, listOf(TypedBlock("x"))))
        assertTrue(!json.contains("\"spans\"") && !json.contains("\"assetId\""))
        val future = """{"version":1,"blocks":[{"id":"x","type":"PARAGRAPH","text":"hi","futureField":42}],"other":true}"""
        assertEquals("hi", TypedCodec.decode(future).blocks.single().text)
    }

    @Test fun sanitizeRepairsBrokenContent() {
        val broken = TypedDocument(
            1,
            listOf(
                TypedBlock("a", BlockType.HEADING, "x", level = 9),
                TypedBlock("a", BlockType.PARAGRAPH, "abc", listOf(TextSpan(1, 99, bold = true))),
                TypedBlock("", BlockType.TABLE, rows = listOf(listOf("1"), listOf("2", "3"))),
                TypedBlock("code", BlockType.CODE, "x", listOf(TextSpan(0, 1, bold = true))),
            ),
        )
        val fixed = TypedCodec.sanitize(broken)
        assertEquals(3, fixed.blocks[0].level)
        assertEquals(4, fixed.blocks.map { it.id }.toSet().size)
        assertTrue(fixed.blocks.none { it.id.isBlank() })
        assertEquals(listOf(TextSpan(1, 3, bold = true)), fixed.blocks[1].spans)
        assertEquals(listOf(listOf("1", ""), listOf("2", "3")), fixed.blocks[2].rows)
        assertTrue(fixed.blocks[3].spans.isEmpty())
    }

    @Test fun malformedJsonIsRejectedNotSwallowed() {
        assertNull(TypedCodec.decodeOrNull("{\"blocks\": [ {\"id\": "))
        assertNull(TypedCodec.decodeOrNull(""))
    }

    @Test fun tableRowAndColumnOperations() {
        var t = TableOps.newTable(2, 2)
        t = TableOps.setCell(t, 0, 0, "Name")
        t = TableOps.setCell(t, 0, 1, "Age")
        t = TableOps.insertRow(t, 2)
        t = TableOps.setCell(t, 2, 0, "Ann")
        assertEquals(3, t.size)
        t = TableOps.insertColumn(t, 1)
        assertEquals(listOf("Name", "", "Age"), t[0])
        t = TableOps.removeColumn(t, 1)
        assertEquals(listOf("Name", "Age"), t[0])
        t = TableOps.removeRow(t, 1)
        assertEquals(listOf(listOf("Name", "Age"), listOf("Ann", "")), t)
        // Never below one row / one column.
        var single = TableOps.newTable(1, 1)
        single = TableOps.removeRow(single, 0)
        single = TableOps.removeColumn(single, 0)
        assertEquals(listOf(listOf("")), single)
        // Out-of-range edits are ignored.
        assertEquals(t, TableOps.setCell(t, 9, 9, "x"))
        assertEquals(listOf(listOf("Ann", ""), listOf("Name", "Age")), TableOps.moveRow(t, 1, 0))
    }

    @Test fun tableTextExports() {
        val t = listOf(listOf("A", "B|C"), listOf("1", ""))
        assertEquals("A | B|C\n1 | ", TableOps.toPlainText(t))
        assertEquals("| A | B\\|C |\n| --- | --- |\n| 1 |  |", TableOps.toMarkdown(t))
        assertEquals(listOf(listOf("")), TableOps.normalize(emptyList()))
    }
}
