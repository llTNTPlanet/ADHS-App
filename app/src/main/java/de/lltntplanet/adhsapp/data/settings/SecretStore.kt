package de.lltntplanet.adhsapp.data.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Zugangsdaten, die nicht in Geräte-Backups landen sollen (siehe res/xml/backup_rules.xml und
 * res/xml/data_extraction_rules.xml).
 * Liegt im privaten App-Speicher, den nur diese App lesen kann.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _githubToken = MutableStateFlow(prefs.getString(KEY_GITHUB_TOKEN, null))
    /** Token für Updates aus einem privaten GitHub-Repository (fine-grained, nur Lesezugriff). */
    val githubToken: StateFlow<String?> = _githubToken.asStateFlow()

    fun setGithubToken(token: String?) {
        val value = token?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { if (value == null) remove(KEY_GITHUB_TOKEN) else putString(KEY_GITHUB_TOKEN, value) }
        _githubToken.value = value
    }

    companion object {
        const val FILE = "secrets"
        private const val KEY_GITHUB_TOKEN = "github_token"
    }
}
