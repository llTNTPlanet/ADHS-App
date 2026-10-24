package de.lltntplanet.adhsapp.data.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import de.lltntplanet.adhsapp.data.settings.SettingsRepository
import kotlinx.coroutines.tasks.await

enum class GoogleFeature(val label: String, val scopes: List<String>) {
    HEALTH(
        "Google Health",
        listOf(
            "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
            "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
            "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
        ),
    ),
    TASKS("Google Tasks", listOf("https://www.googleapis.com/auth/tasks")),
}

sealed interface TokenResult {
    data class Token(val accessToken: String, val account: String?) : TokenResult
    /** Google muss einmalig um Erlaubnis fragen – nur mit sichtbarer Activity möglich. */
    data class NeedsConsent(val pendingIntent: PendingIntent) : TokenResult
    data class Failed(val message: String) : TokenResult
}

/**
 * Holt Zugriffstokens über die Google-Identity-Dienste (AuthorizationClient).
 * Ein Client-Secret in der App ist nicht nötig: Google erkennt die App am Paketnamen und
 * am SHA-1-Fingerabdruck, die im Android-OAuth-Client der Google Cloud Console hinterlegt sind.
 */
class GoogleAuth(private val context: Context, private val settings: SettingsRepository) {

    private val client get() = Identity.getAuthorizationClient(context)

    private suspend fun request(feature: GoogleFeature): AuthorizationRequest {
        val account = settings.current().googleAccount
        return AuthorizationRequest.builder()
            .setRequestedScopes(feature.scopes.map { Scope(it) })
            .apply { if (account != null) setAccount(Account(account, "com.google")) }
            .build()
    }

    suspend fun token(feature: GoogleFeature): TokenResult = try {
        handle(client.authorize(request(feature)).await())
    } catch (e: ApiException) {
        Log.w(TAG, "authorize fehlgeschlagen", e)
        TokenResult.Failed(describe(e))
    } catch (e: Exception) {
        Log.w(TAG, "authorize fehlgeschlagen", e)
        TokenResult.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Ergebnis des Zustimmungsdialogs auswerten. */
    suspend fun resultFromIntent(data: Intent?): TokenResult = try {
        handle(client.getAuthorizationResultFromIntent(data))
    } catch (e: ApiException) {
        TokenResult.Failed(describe(e))
    }

    private suspend fun handle(result: AuthorizationResult): TokenResult {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return TokenResult.NeedsConsent(pending)
        val token = result.accessToken ?: return TokenResult.Failed("Kein Zugriffstoken erhalten")
        val email = result.toGoogleSignInAccount()?.email
        if (email != null && email != settings.current().googleAccount) {
            settings.update { it.copy(googleAccount = email) }
        }
        return TokenResult.Token(token, email)
    }

    /** Abgelaufenes oder ungültiges Token aus dem Cache entfernen. */
    suspend fun invalidate(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
            .onFailure { Log.w(TAG, "clearToken fehlgeschlagen", it) }
    }

    suspend fun revoke(feature: GoogleFeature) {
        val account = settings.current().googleAccount ?: return
        runCatching {
            client.revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(Account(account, "com.google"))
                    .setScopes(feature.scopes.map { Scope(it) })
                    .build(),
            ).await()
        }.onFailure { Log.w(TAG, "revokeAccess fehlgeschlagen", it) }
    }

    private fun describe(e: ApiException): String = when {
        e.statusCode == CommonStatusCodes.INTERNAL_ERROR || e.message?.contains("UNREGISTERED_ON_API_CONSOLE") == true ->
            "Google kennt diese App noch nicht. Einmalig in der Google Cloud Console einen Android-OAuth-Client anlegen " +
                "(Paketname de.lltntplanet.adhsapp + SHA-1 der installierten Version) und die passende API aktivieren – " +
                "Anleitung in der README unter „Google Health und Google Tasks verbinden“."
        else -> when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR ->
            "Google-Anmeldung ist noch nicht eingerichtet (Fehler 10). Prüfe in der Google Cloud Console den " +
                "Android-OAuth-Client: Paketname de.lltntplanet.adhsapp und SHA-1-Fingerabdruck deiner App."
        CommonStatusCodes.NETWORK_ERROR -> "Keine Internetverbindung."
        CommonStatusCodes.CANCELED, 16 -> "Anmeldung abgebrochen."
        CommonStatusCodes.SIGN_IN_REQUIRED -> "Bitte mit Google anmelden."
        else -> "Google-Fehler ${e.statusCode}: ${e.message}"
        }
    }

    companion object {
        private const val TAG = "GoogleAuth"
    }
}
