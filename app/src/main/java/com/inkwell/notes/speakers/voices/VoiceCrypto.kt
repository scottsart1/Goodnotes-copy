package com.inkwell.notes.speakers.voices

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a dedicated Android Keystore key that never leaves the Keystore. Used only
 * for voice data (voiceprints and reference clips). Blobs are: version byte, 12-byte IV,
 * ciphertext with tag. The associated data binds each blob to its file, so files cannot be
 * swapped between people. The key does not require an unlocked device: identification runs in
 * the background after a meeting, often with the screen off.
 */
internal class VoiceCrypto(private val alias: String = ALIAS) {
    sealed interface Opened {
        class Ok(val bytes: ByteArray) : Opened

        /** Encrypted with a key that no longer exists, or corrupt: unrecoverable. */
        data object Unreadable : Opened

        /** The Keystore is temporarily unusable; keep the file and try later. */
        data object Unavailable : Opened
    }

    @Synchronized
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val ct = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected IV size" }
        return byteArrayOf(VERSION) + iv + ct
    }

    @Synchronized
    fun open(blob: ByteArray, aad: ByteArray): Opened {
        if (blob.size < 1 + IV_BYTES + TAG_BYTES || blob[0] != VERSION) return Opened.Unreadable
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return Opened.Unreadable, GCMParameterSpec(TAG_BYTES * 8, blob, 1, IV_BYTES))
            cipher.updateAAD(aad)
            Opened.Ok(cipher.doFinal(blob, 1 + IV_BYTES, blob.size - 1 - IV_BYTES))
        } catch (_: AEADBadTagException) {
            Opened.Unreadable
        } catch (e: java.security.InvalidKeyException) {
            // KeyPermanentlyInvalidatedException is an InvalidKeyException: the key is gone for good.
            Log.w(TAG, "Voice key unusable: ${e.javaClass.simpleName}")
            Opened.Unreadable
        } catch (e: Exception) {
            Log.w(TAG, "Voice key temporarily unavailable: ${e.javaClass.simpleName}")
            Opened.Unavailable
        }
    }

    /** Deletes the key; data sealed with it becomes unreadable. A new key is made on next use. */
    @Synchronized
    fun rotate() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        }.onFailure { Log.w(TAG, "Could not delete the voice key: ${it.javaClass.simpleName}") }
    }

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun key(): SecretKey {
        existingKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val TAG = "VoiceCrypto"
        const val ALIAS = "inkwell.voices.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private const val VERSION: Byte = 1
    }
}
