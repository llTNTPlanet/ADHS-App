package de.lltntplanet.adhsapp.data.google

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime

@Serializable
data class GTaskList(val id: String, val title: String = "")

@Serializable
data class GTaskListsResponse(val items: List<GTaskList> = emptyList(), val nextPageToken: String? = null)

@Serializable
data class GTask(
    val id: String? = null,
    val title: String? = null,
    val notes: String? = null,
    /** "needsAction" oder "completed" */
    val status: String? = null,
    /** RFC 3339 – Google speichert nur das Datum, die Uhrzeit wird ignoriert. */
    val due: String? = null,
    val completed: String? = null,
    val deleted: Boolean? = null,
    val hidden: Boolean? = null,
    val updated: String? = null,
) {
    val isCompleted: Boolean get() = status == "completed"

    val dueDate: LocalDate? get() = due?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }

    companion object {
        fun dueString(date: LocalDate): String = "${date}T00:00:00.000Z"
    }
}

@Serializable
data class GTasksResponse(val items: List<GTask> = emptyList(), val nextPageToken: String? = null)

/** Google Tasks API v1 (https://developers.google.com/workspace/tasks/reference/rest). */
class GoogleTasksApi(
    private val http: GoogleHttp,
    private val baseUrl: String = "https://tasks.googleapis.com/tasks/v1/",
) {
    suspend fun lists(): List<GTaskList> {
        val result = mutableListOf<GTaskList>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val url = "${baseUrl}users/@me/lists".toHttpUrl().newBuilder()
                .addQueryParameter("maxResults", "100")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build().toString()
            val response = GoogleJson.decodeFromString<GTaskListsResponse>(http.get(GoogleFeature.TASKS, url))
            result += response.items
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() } ?: return result
        }
        return result
    }

    suspend fun tasks(listId: String): List<GTask> {
        val result = mutableListOf<GTask>()
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val url = "${baseUrl}lists/${enc(listId)}/tasks".toHttpUrl().newBuilder()
                .addQueryParameter("maxResults", "100")
                .addQueryParameter("showCompleted", "true")
                .addQueryParameter("showHidden", "false")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build().toString()
            val response = GoogleJson.decodeFromString<GTasksResponse>(http.get(GoogleFeature.TASKS, url))
            result += response.items
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() } ?: return result
        }
        return result
    }

    suspend fun insert(listId: String, task: GTask): GTask =
        GoogleJson.decodeFromString(
            http.post(GoogleFeature.TASKS, "${baseUrl}lists/${enc(listId)}/tasks", GoogleJson.encodeToString(GTask.serializer(), task)),
        )

    suspend fun patch(listId: String, taskId: String, patch: JsonObject): GTask =
        GoogleJson.decodeFromString(
            http.patch(GoogleFeature.TASKS, "${baseUrl}lists/${enc(listId)}/tasks/${enc(taskId)}", patch.toString()),
        )

    suspend fun delete(listId: String, taskId: String) {
        http.delete(GoogleFeature.TASKS, "${baseUrl}lists/${enc(listId)}/tasks/${enc(taskId)}")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val MAX_PAGES = 10
    }
}
