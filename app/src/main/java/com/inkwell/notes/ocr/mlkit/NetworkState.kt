package com.inkwell.notes.ocr.mlkit

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Current connectivity, for deciding whether a model download may start now. */
internal object NetworkState {
    private fun caps(context: Context): NetworkCapabilities? = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm?.getNetworkCapabilities(cm.activeNetwork)
    } catch (e: SecurityException) {
        null
    }

    fun isOnline(context: Context): Boolean =
        caps(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun isUnmetered(context: Context): Boolean {
        val c = caps(context) ?: return false
        if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
    }
}
