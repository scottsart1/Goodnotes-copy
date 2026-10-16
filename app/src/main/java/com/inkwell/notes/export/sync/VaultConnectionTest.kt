package com.inkwell.notes.export.sync

import okhttp3.OkHttpClient

/** "Test connection": log in with the given password, then ping with the new token. */
object VaultConnectionTest {
    data class Success(val message: String, val session: VaultSession)

    suspend fun run(http: OkHttpClient, endpoint: VaultEndpoint, password: String, clock: () -> Long = System::currentTimeMillis): Result<Success> {
        if (password.isEmpty()) return Result.failure(SyncCheckFailure("Enter the vault password."))
        val credentials = object : VaultCredentials {
            @Volatile var current: VaultSession? = null
            override fun password(): String = password
            override fun session(): VaultSession? = current
            override fun saveSession(session: VaultSession?) {
                current = session
            }
        }
        val client = VaultClient(http, endpoint, credentials, clock)
        return try {
            val session = client.login(password)
            credentials.current = session
            val name = client.ping().ifBlank { DEFAULT_NAME }
            Result.success(Success("Connected to $name", credentials.current ?: session))
        } catch (e: VaultException) {
            Result.failure(SyncCheckFailure(message(e, endpoint), e))
        }
    }

    fun message(e: VaultException, endpoint: VaultEndpoint): String = when (e) {
        is VaultException.WrongPassword -> "Wrong password."
        is VaultException.MissingPassword -> "Enter the vault password."
        is VaultException.Locked -> "Locked after too many attempts. Try again in ${VaultException.waitText(e.retryAfterSeconds)}."
        is VaultException.NotAVault -> "Not a vault link: ${endpoint.base.host} answered 404. Check the private link."
        is VaultException.Unreachable -> "Can't reach ${endpoint.base.host}. Check the internet connection."
        is VaultException.InsecureConnection -> "Couldn't make a secure connection to ${endpoint.base.host}."
        is VaultException.NotConfigured -> "The vault on ${endpoint.base.host} isn't set up yet."
        is VaultException.Server -> "The vault had a problem (HTTP ${e.code}). Try again later."
        is VaultException.BadResponse -> "${endpoint.base.host} didn't answer like a notes vault. Check the link."
        is VaultException.Unauthorized -> "The vault accepted the password but refused the session. Try again."
        else -> e.message ?: "The connection test failed."
    }

    const val DEFAULT_NAME = "Your Pit Box · Notes"
}

/** A failed connection or folder check; [message] is ready to show. */
class SyncCheckFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
