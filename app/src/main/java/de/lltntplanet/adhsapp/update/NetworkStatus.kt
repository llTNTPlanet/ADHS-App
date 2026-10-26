package de.lltntplanet.adhsapp.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Fragt Android, ob das aktive Netz Internet hat (und Android es als funktionierend bestätigt hat). */
object NetworkStatus {
    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
