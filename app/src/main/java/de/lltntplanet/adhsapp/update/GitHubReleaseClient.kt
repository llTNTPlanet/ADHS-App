package de.lltntplanet.adhsapp.update

import de.lltntplanet.adhsapp.data.google.GoogleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val id: Long,
    val name: String,
    val size: Long = 0,
    /** API-Adresse des Assets; mit `Accept: application/octet-stream` liefert sie die Datei (auch bei privaten Repos). */
    val url: String,
    /** Von GitHub berechnete Prüfsumme, z. B. "sha256:ab12…". */
    val digest: String? = null,
)

/** Eine neuere Version, die installiert werden kann. */
@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
    val htmlUrl: String?,
)

/** Fehler beim Abfragen von GitHub, mit verständlicher Meldung. */
class UpdateCheckException(message: String, val code: Int = 0) : IOException(message)

/**
 * Liest das neueste Release eines GitHub-Repositorys. Für private Repos ist ein Token
 * (fine-grained, nur "Contents: Read") nötig.
 */
class GitHubReleaseClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val apiBase: String = "https://api.github.com/",
) {
    suspend fun latestRelease(owner: String, repo: String, token: String?): GitHubRelease = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${apiBase}repos/$owner/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}") }
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> GoogleJson.decodeFromString<GitHubRelease>(text)
                response.code == 404 -> throw UpdateCheckException(
                    if (token.isNullOrBlank()) {
                        "Keine Version gefunden. Ist das Repository privat? Dann bitte in den Einstellungen einen GitHub-Token eintragen."
                    } else {
                        "Noch keine Version veröffentlicht – oder der Token hat keinen Zugriff auf das Repository."
                    },
                    404,
                )
                response.code == 401 -> throw UpdateCheckException("GitHub-Token ungültig oder abgelaufen.", 401)
                response.code == 403 && response.header("x-ratelimit-remaining") == "0" ->
                    throw UpdateCheckException("Zu viele Anfragen an GitHub – später erneut versuchen.", 403)
                else -> throw UpdateCheckException("GitHub antwortet mit Fehler ${response.code}.", response.code)
            }
        }
    }

    /**
     * Lädt die APK herunter, prüft Größe und (falls von GitHub geliefert) SHA-256.
     * [onProgress] erhält Werte von 0 bis 1.
     */
    suspend fun download(info: UpdateInfo, token: String?, target: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(info.apkUrl)
                .header("Accept", "application/octet-stream")
                .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer ${token.trim()}") }
                .build()
            val digest = MessageDigest.getInstance("SHA-256")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw UpdateCheckException("Download fehlgeschlagen (${response.code}).", response.code)
                val body = response.body ?: throw UpdateCheckException("Download fehlgeschlagen (leere Antwort).")
                val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                var read = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (info.apkSize > 0 && read != info.apkSize) {
                    target.delete()
                    throw UpdateCheckException("Download unvollständig – bitte erneut versuchen.")
                }
            }
            val actual = digest.digest().joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
            if (info.sha256 != null && !info.sha256.equals(actual, ignoreCase = true)) {
                target.delete()
                throw UpdateCheckException("Prüfsumme stimmt nicht – Update wurde nicht installiert.")
            }
            target
        }

    companion object {
        private val VERSION_CODE = Regex("""versionCode:\s*(\d+)""")
        private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Macht aus einem Release ein [UpdateInfo], wenn es neuer als [currentVersionCode] ist.
         * Die Release-Pipeline schreibt die Versionsnummer als `<!-- versionCode: 42 -->` in den Text.
         */
        fun toUpdate(release: GitHubRelease, currentVersionCode: Int): UpdateInfo? {
            if (release.draft || release.prerelease) return null
            val body = release.body.orEmpty()
            val code = VERSION_CODE.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (code <= currentVersionCode) return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
            return UpdateInfo(
                versionCode = code,
                versionName = release.tagName.removePrefix("v"),
                notes = body.replace(HTML_COMMENT, "").trim(),
                apkUrl = apk.url,
                apkSize = apk.size,
                sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                htmlUrl = release.htmlUrl,
            )
        }
    }
}
