package com.inkwell.notes.speakers.ui

import com.inkwell.notes.core.model.Person
import com.inkwell.notes.speakers.ui.logic.BlockMove
import com.inkwell.notes.speakers.ui.logic.NameSuggestions
import com.inkwell.notes.speakers.ui.logic.NamingDraft
import com.inkwell.notes.speakers.ui.logic.NamingOp
import com.inkwell.notes.speakers.ui.logic.NamingPlan
import com.inkwell.notes.speakers.ui.logic.SpeakerEdits
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun person(id: String, name: String, isMe: Boolean = false, recordings: Int = 0, samples: Int = 1) =
    Person(id, name, isMe, 0, 0, 0, samples, recordings)

class NamingPlanTest {
    private val me = person("me", "Sarthak", isMe = true, recordings = 3)
    private val priya = person("p1", "Priya Shah", recordings = 5)
    private val tom = person("p2", "Tom", recordings = 1)
    private val people = listOf(priya, tom, me)

    @Test
    fun suggestionsPutTheOwnerFirstThenPrefixThenWordMatches() {
        assertEquals(listOf("me", "p1", "p2"), NameSuggestions.rank(people, "").map { it.id })
        assertEquals(listOf("p1"), NameSuggestions.rank(people, "pri").map { it.id })
        assertEquals(listOf("p1"), NameSuggestions.rank(people, "  SH ").map { it.id })
        val s = person("p3", "Sam")
        val ranked = NameSuggestions.rank(people + s, "s").map { it.id }
        assertEquals("owner first, then name prefixes, then later-word prefixes", listOf("me", "p3", "p1"), ranked)
        assertTrue(NameSuggestions.rank(people, "zz").isEmpty())
        assertEquals(listOf("p1", "p2"), NameSuggestions.rank(people, "", excludeIds = setOf("me")).map { it.id })
        assertEquals(1, NameSuggestions.rank(people, "", limit = 1).size)
    }

    @Test
    fun namesAreCleanedAndExactMatchesIgnoreCase() {
        assertEquals("Ana Lee", NameSuggestions.clean("  Ana \n  Lee "))
        assertEquals(NameSuggestions.MAX_NAME, NameSuggestions.clean("x".repeat(200)).length)
        val emoji = "a".repeat(NameSuggestions.MAX_NAME - 1) + "😀"
        assertFalse("never cut a surrogate pair", Character.isHighSurrogate(NameSuggestions.clean(emoji).last()))
        assertEquals("p2", NameSuggestions.exact(people, " tom ")?.id)
        assertNull(NameSuggestions.exact(people, ""))
    }

    @Test
    fun draftsStartFromTheStoredSpeakers() {
        val d = NamingPlan.initial(
            listOf(speaker("S1", "Sarthak", personId = "me"), speaker("S2"), speaker("S3", "Tom", personId = "p2", confirmed = false, confidence = 0.8f)),
            meId = "me",
        )
        assertEquals(NamingDraft("Sarthak", "me", isMe = true, wasMe = true), d["S1"])
        assertEquals(NamingDraft(), d["S2"])
        assertEquals("an automatic match is offered, not pre-typed", NamingDraft(), d["S3"])
    }

    @Test
    fun typingANewNameNamesTheVoiceAndLeftoversDismiss() {
        val speakers = listOf(speaker("S1"), speaker("S2"))
        var d = NamingPlan.initial(speakers, null)
        d = NamingPlan.edit(d, "S1", " Ana ", people)
        assertEquals(listOf(NamingOp.Name("S1", "Ana", false), NamingOp.Dismiss), NamingPlan.operations(speakers, d))
        assertTrue(NamingPlan.hasChanges(speakers, d))
        assertFalse(NamingPlan.hasChanges(speakers, NamingPlan.initial(speakers, null)))
        assertEquals("untouched unnamed voices only dismiss", listOf(NamingOp.Dismiss), NamingPlan.operations(speakers, NamingPlan.initial(speakers, null)))
    }

    @Test
    fun pickingASuggestionAssignsAndEditingAwayForgetsIt() {
        val speakers = listOf(speaker("S1"))
        var d = NamingPlan.pick(NamingPlan.initial(speakers, null), "S1", priya)
        assertEquals(listOf(NamingOp.Assign("S1", "p1")), NamingPlan.operations(speakers, d))
        d = NamingPlan.edit(d, "S1", "priya shah", people)
        assertEquals("same name, other case: still the picked person", "p1", d["S1"]!!.personId)
        d = NamingPlan.edit(d, "S1", "Priya S", people)
        assertNull(d["S1"]!!.personId)
        assertEquals(listOf(NamingOp.Name("S1", "Priya S", false)), NamingPlan.operations(speakers, d))
    }

    @Test
    fun pickingTheAutomaticMatchConfirmsIt() {
        val speakers = listOf(speaker("S1", "Tom", personId = "p2", confirmed = false, confidence = 0.7f))
        val d = NamingPlan.pick(NamingPlan.initial(speakers, null), "S1", tom)
        assertEquals(listOf(NamingOp.Confirm("S1")), NamingPlan.operations(speakers, d))
        // Left alone, it stays an automatic match and nothing is dismissed.
        assertTrue(NamingPlan.operations(speakers, NamingPlan.initial(speakers, null)).isEmpty())
    }

    @Test
    fun thisIsMeIsExclusive() {
        val speakers = listOf(speaker("S1"), speaker("S2"))
        var d = NamingPlan.initial(speakers, null)
        d = NamingPlan.edit(d, "S1", "Sarthak", people)
        d = NamingPlan.toggleMe(d, "S1", true)
        d = NamingPlan.edit(d, "S2", "Ana", people)
        d = NamingPlan.toggleMe(d, "S2", true)
        assertFalse(d["S1"]!!.isMe)
        assertTrue(d["S2"]!!.isMe)
        assertEquals(
            listOf(NamingOp.Name("S1", "Sarthak", false), NamingOp.Name("S2", "Ana", true)),
            NamingPlan.operations(speakers, d),
        )
    }

    @Test
    fun movingThisIsMeFromAConfirmedVoiceIsOneOwnerChange() {
        // S1 is the owner; the user says S2 (already Tom) is them instead.
        val speakers = listOf(speaker("S1", "Sarthak", personId = "me"), speaker("S2", "Tom", personId = "p2"))
        var d = NamingPlan.initial(speakers, meId = "me")
        d = NamingPlan.toggleMe(d, "S2", true)
        assertEquals(listOf(NamingOp.SetMe("p2")), NamingPlan.operations(speakers, d))
        // Switching it off with nobody else marked clears the owner.
        val off = NamingPlan.toggleMe(NamingPlan.initial(speakers, "me"), "S1", false)
        assertEquals(listOf(NamingOp.SetMe(null)), NamingPlan.operations(speakers, off))
    }

    @Test
    fun pickingTheOwnerTurnsOnThisIsMe() {
        val speakers = listOf(speaker("S1"), speaker("S2"))
        val d = NamingPlan.pick(NamingPlan.initial(speakers, "me"), "S2", me)
        assertTrue(d["S2"]!!.isMe)
        assertEquals(listOf(NamingOp.Assign("S2", "me"), NamingOp.SetMe("me"), NamingOp.Dismiss), NamingPlan.operations(speakers, d))
    }

    @Test
    fun mergeKeepsEditsAndFollowsTheStore() {
        val before = listOf(speaker("S1"), speaker("S2", "Tom", personId = "p2", confirmed = false))
        var d = NamingPlan.edit(NamingPlan.initial(before, null), "S1", "Ana", people)
        // Meanwhile S2 was confirmed elsewhere and a third voice appeared; S1's typing survives.
        val after = listOf(speaker("S1"), speaker("S2", "Tom", personId = "p2"), speaker("S3"))
        d = NamingPlan.merge(d, after, null)
        assertEquals("Ana", d["S1"]!!.text)
        assertEquals("Tom", d["S2"]!!.text)
        assertEquals(NamingDraft(), d["S3"])
        assertEquals(setOf("S1", "S2"), NamingPlan.merge(d, after.take(2), null).keys)
    }

    @Test
    fun draftsSurviveASerializationRoundTrip() {
        val d = mapOf("S1" to NamingDraft("Ana", null, isMe = true, touched = true))
        val ser = MapSerializer(String.serializer(), NamingDraft.serializer())
        assertEquals(d, Json.decodeFromString(ser, Json.encodeToString(ser, d)))
    }

    @Test
    fun movingABlockReusesVoicesBeforeAddingOne() {
        val speakers = listOf(speaker("S1", "Priya Shah", personId = "p1"), speaker("S2"))
        assertEquals(BlockMove.ToVoice("S1"), SpeakerEdits.moveToPerson(speakers, priya))
        assertEquals(BlockMove.ToPerson("S3", "p2"), SpeakerEdits.moveToPerson(speakers, tom))
        assertEquals(BlockMove.ToVoice("S1"), SpeakerEdits.moveToName(speakers, people, "priya shah"))
        assertEquals(BlockMove.ToPerson("S3", "p2"), SpeakerEdits.moveToName(speakers, people, "TOM"))
        assertEquals(BlockMove.ToNewName("S3", "Ana"), SpeakerEdits.moveToName(speakers, people, " Ana "))
        assertNull(SpeakerEdits.moveToName(speakers, people, "  "))
        assertEquals(listOf("S2"), SpeakerEdits.mergeTargets(speakers, "S1").map { it.label })
    }
}
