package com.inkwell.notes.core.storage

import com.inkwell.notes.core.model.AudioAnchor
import com.inkwell.notes.core.model.PageContent
import com.inkwell.notes.core.model.StickyItem
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * StickyItem gained ProtoBuf field 11 (the recording anchor) after pages were already on
 * disk. Old pages must load with no anchor, and a page written by this build must still load
 * in a build that does not know field 11 (sync to another tablet, a downgrade), keeping every
 * sticky note.
 */
@OptIn(ExperimentalSerializationApi::class)
class StickyAudioProtoCompatTest {
    /** StickyItem as it was before the anchor existed (fields 1-10). */
    @Serializable
    private data class OldStickyItem(
        @ProtoNumber(1) val id: String,
        @ProtoNumber(2) val x: Float,
        @ProtoNumber(3) val y: Float,
        @ProtoNumber(4) val width: Float = 180f,
        @ProtoNumber(5) val height: Float = 180f,
        @ProtoNumber(6) val text: String = "",
        @ProtoNumber(7) val color: Int = 0xFFFFE89A.toInt(),
        @ProtoNumber(8) val z: Int = 0,
        @ProtoNumber(9) val createdAt: Long = 0,
        @ProtoNumber(10) val groupId: String? = null,
    )

    /** Only the sticky list of PageContent; other lists are left out to prove unknown fields are skipped. */
    @Serializable
    private data class OldPageContent(
        @ProtoNumber(1) val version: Int = 1,
        @ProtoNumber(6) val stickies: List<OldStickyItem> = emptyList(),
    )

    private val oldProto = ProtoBuf { encodeDefaults = false }

    private val anchored = StickyItem("st1", 10f, 20f, 200f, 150f, "Action: ship", 0xFFABCDEF.toInt(), z = 3, createdAt = 99, groupId = "g1", audio = AudioAnchor("rec-1", 61_500))
    private val plain = StickyItem("st2", 1f, 2f, text = "no audio")

    @Test fun pagesWrittenBeforeTheAnchorLoadWithNullAudio() {
        val old = OldPageContent(stickies = listOf(OldStickyItem("st1", 10f, 20f, 200f, 150f, "Action: ship", 0xFFABCDEF.toInt(), 3, 99, "g1")))
        val bytes = oldProto.encodeToByteArray(old)
        val content = PageContentStore.decodeOrNull(bytes)!!
        val sticky = content.stickies.single()
        assertEquals(anchored.copy(audio = null), sticky)
        assertNull(sticky.audio)
    }

    @Test fun anchorRoundTripsThroughThePageStoreEncoding() {
        val content = PageContent(stickies = listOf(anchored, plain))
        val back = PageContentStore.decodeOrNull(PageContentStore.encode(content))!!
        assertEquals(listOf(anchored, plain), back.stickies)
        assertEquals(AudioAnchor("rec-1", 61_500), back.stickies[0].audio)
        assertNull(back.stickies[1].audio)
    }

    @Test fun buildsWithoutField11SkipTheAnchorAndKeepTheStickyNote() {
        val bytes = PageContentStore.encode(PageContent(stickies = listOf(anchored, plain)))
        val old = oldProto.decodeFromByteArray<OldPageContent>(bytes)
        assertEquals(2, old.stickies.size)
        assertEquals(OldStickyItem("st1", 10f, 20f, 200f, 150f, "Action: ship", 0xFFABCDEF.toInt(), 3, 99, "g1"), old.stickies[0])
        assertEquals("no audio", old.stickies[1].text)
    }

    @Test fun anchorIsOnlyWrittenWhenPresent() {
        val with = PageContentStore.encode(PageContent(stickies = listOf(anchored)))
        val without = PageContentStore.encode(PageContent(stickies = listOf(anchored.copy(audio = null))))
        // Field 11 costs bytes only when set, so pages without anchors are byte-identical to before.
        assert(with.size > without.size)
        assertEquals(oldProto.encodeToByteArray(OldPageContent(stickies = listOf(OldStickyItem("st1", 10f, 20f, 200f, 150f, "Action: ship", 0xFFABCDEF.toInt(), 3, 99, "g1")))).toList(), without.toList())
    }
}
