package com.inkwell.notes.export.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What happens to the saved vault password when the saved link changes (see [VaultPassword.keptAcross]). */
class VaultPasswordTest {
    private val a = "https://yourpitbox.com/notes/Lk7xQ2pLmn0"
    private val aWww = "https://www.yourpitbox.com/notes/Lk7xQ2pLmn0"
    private val typo = "https://yourpitbox.com/notes/Lk7xQ2pLmn"
    private val b = "https://yourpitbox.com/notes/Zz9yW8vUts1"
    private fun fp(link: String) = VaultUrl.endpointOrNull(link)!!.fingerprint

    @Test
    fun `a link to another vault forgets the password`() {
        assertFalse("tied to the old link", VaultPassword.keptAcross(fp(a), oldLink = a, newLink = b))
        assertFalse("untied: belongs to the old link", VaultPassword.keptAcross(null, oldLink = a, newLink = b))
        assertFalse("tied to a mistyped link that was corrected", VaultPassword.keptAcross(fp(typo), oldLink = typo, newLink = a))
    }

    @Test
    fun `the same vault on its www host keeps the password`() {
        assertTrue(VaultPassword.keptAcross(fp(a), oldLink = a, newLink = aWww))
        assertTrue(VaultPassword.keptAcross(null, oldLink = aWww, newLink = a))
    }

    @Test
    fun `a password entered for the new link keeps it`() {
        // Typed while the new link was still in the field, before that link was saved.
        assertTrue(VaultPassword.keptAcross(fp(b), oldLink = a, newLink = b))
        // Typed while no link was set: it is for the first link entered.
        assertTrue(VaultPassword.keptAcross(null, oldLink = "", newLink = b))
    }

    @Test
    fun `clearing the link keeps the password for its vault`() {
        assertTrue(VaultPassword.keptAcross(fp(a), oldLink = a, newLink = ""))
        assertTrue(VaultPassword.keptAcross(null, oldLink = a, newLink = ""))
        // Entering the same vault again keeps it; another vault forgets it.
        assertTrue(VaultPassword.keptAcross(fp(a), oldLink = "", newLink = a))
        assertFalse(VaultPassword.keptAcross(fp(a), oldLink = "", newLink = b))
    }
}
