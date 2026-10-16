package com.inkwell.notes.export.sync

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VaultDocExistenceAndFolderCollisionTest {
    private val vault = FakeVault()

    @After
    fun tearDown() = vault.close()

    @Test
    fun `hasDoc is true for a stored document and false for a JSON not_found`() = runBlocking {
        vault.docIds += "doc-1"
        val client = vault.client(MemoryCredentials())
        assertTrue(client.hasDoc("doc-1"))
        assertFalse(client.hasDoc("doc-2"))
        assertEquals("GET /api/docs/doc-2", vault.calls().last())
    }

    @Test
    fun `hasDoc on a URL that is not a vault still reports NotAVault`() = runBlocking {
        val wrong = VaultEndpoint.insecureForTests(vault.server.url("/notes/not-the-link"))
        val client = VaultClient(FakeVault.httpClient(), wrong, MemoryCredentials())
        try {
            client.hasDoc("doc-1")
            fail("expected NotAVault")
        } catch (e: VaultException.NotAVault) {
            // A plain 404 page must not be mistaken for "document missing".
        }
    }

    @Test
    fun `titles that sanitise to the same segment get distinct directories`() {
        val a = FolderNaming.documentDir(emptyList(), "Q&A: Lecture", "0b6f2c1e-8a4d")
        val b = FolderNaming.documentDir(emptyList(), "Q&A_ Lecture", "7c1d9e0f-1111")
        assertNotEquals(a, b)
        assertEquals("Q&A_ Lecture", b.last())
        assertEquals("Q&A_ Lecture (0b6f2c)", a.last())
    }

    @Test
    fun `clean titles keep their plain directory name`() {
        assertEquals(
            listOf("Inkwell", "Economics", "20261015_Financial Markets"),
            FolderNaming.documentDir(listOf("Economics"), "20261015_Financial Markets", "0b6f2c1e"),
        )
    }

    @Test
    fun `a truncated long title stays within the segment limit with its suffix`() {
        val seg = FolderNaming.documentDir(emptyList(), "x".repeat(150), "abcdef123").last()
        assertTrue(seg.endsWith(" (abcdef)"))
        assertTrue(seg.length <= 100)
    }
}
