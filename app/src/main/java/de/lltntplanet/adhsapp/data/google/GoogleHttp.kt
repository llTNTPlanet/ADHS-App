package de.lltntplanet.adhsapp.data.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fehlerantwort einer Google-API. */
class GoogleApiException(val code: Int, message: String) : IOException(message)

/** Für den Zugriff fehlt (noch) die Zustimmung des Nutzers. */
class NotAuthorizedException(message: String, val needsConsent: Boolean) : IOException(message)

/** Liefert ein gültiges Token für eine Google-Funktion oder wirft [NotAuthorizedException]. */
interface TokenProvider {
    suspend fun token(feature: GoogleFeature): String
    suspend fun invalidate(token: String)
}

class GoogleAuthTokenProvider(private val auth: GoogleAuth) : TokenProvider {
    override suspend fun token(feature: GoogleFeature): String = when (val r = auth.token(feature)) {
        is TokenResult.Token -> r.accessToken
        is TokenResult.NeedsConsent -> throw NotAuthorizedException("${feature.label} muss neu verbunden werden.", true)
        is TokenResult.Failed -> throw NotAuthorizedException(r.message, false)
    }

    override suspend fun invalidate(token: String) = auth.invalidate(token)
}

val GoogleJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}

/** Kleiner HTTP-Client für Google-REST-APIs mit Token-Erneuerung bei 401. */
class GoogleHttp(
    private val tokens: TokenProvider,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun get(feature: GoogleFeature, url: String): String = send(feature, "GET", url, null)

    suspend fun post(feature: GoogleFeature, url: String, body: String): String = send(feature, "POST", url, body)

    suspend fun patch(feature: GoogleFeature, url: String, body: String): String = send(feature, "PATCH", url, body)

    suspend fun delete(feature: GoogleFeature, url: String): String = send(feature, "DELETE", url, null)

    private suspend fun send(feature: GoogleFeature, method: String, url: String, body: String?): String {
        var token = tokens.token(feature)
        var (code, text) = execute(method, url, body, token)
        if (code == 401) {
            tokens.invalidate(token)
            token = tokens.token(feature)
            val retry = execute(method, url, body, token)
            code = retry.first
            text = retry.second
        }
        if (code !in 200..299) throw GoogleApiException(code, errorMessage(code, text))
        return text
    }

    private suspend fun execute(method: String, url: String, body: String?, token: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toRequestBody(JSON)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .method(method, requestBody ?: if (method == "POST" || method == "PATCH") "".toRequestBody(JSON) else null)
                .build()
            client.newCall(request).execute().use { it.code to (it.body?.string().orEmpty()) }
        }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun errorMessage(code: Int, body: String): String {
            val apiMessage = runCatching {
                GoogleJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
            }.getOrNull()
            val hint = when (code) {
                401 -> "Anmeldung abgelaufen."
                403 -> "Zugriff verweigert – ist die API im Google-Cloud-Projekt aktiviert und dein Konto als Testnutzer eingetragen?"
                404 -> "Nicht gefunden."
                429 -> "Zu viele Anfragen – bitte später erneut versuchen."
                in 500..599 -> "Google-Dienst gerade nicht erreichbar."
                else -> "HTTP $code"
            }
            return if (apiMessage.isNullOrBlank()) hint else "$hint ($apiMessage)"
        }
    }
}
