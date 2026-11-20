package app.wearnotes.network

import app.wearnotes.model.Note
import app.wearnotes.model.NoteColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class DeviceSession(
    val baseUrl: String,
    val token: String,
)

data class OidcConfiguration(
    val clientId: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
)

class NoteApi {
    suspend fun oidcConfiguration(baseUrl: String): OidcConfiguration = withContext(Dispatchers.IO) {
        val response = request(baseUrl.trim().trimEnd('/'), "GET", "/v1/auth/oidc/config", null, null)
        OidcConfiguration(
            clientId = response.getString("clientId"),
            authorizationEndpoint = response.getString("authorizationEndpoint"),
            tokenEndpoint = response.getString("tokenEndpoint"),
        )
    }

    suspend fun exchangeOidcToken(baseUrl: String, idToken: String): DeviceSession = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        val response = request(normalized, "POST", "/v1/auth/oidc/device", null, JSONObject().put("idToken", idToken))
        DeviceSession(normalized, response.getString("token"))
    }

    suspend fun pair(baseUrl: String): DeviceSession = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trim().trimEnd('/')
        val response = request(normalized, "POST", "/v1/auth/devices", null, null)
        DeviceSession(normalized, response.getString("token"))
    }

    suspend fun listNotes(session: DeviceSession, state: String = "active"): List<Note> = withContext(Dispatchers.IO) {
        val response = request(session.baseUrl, "GET", "/v1/notes?state=$state", session.token, null)
        val items = response.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                add(items.getJSONObject(index).toNote())
            }
        }
    }

    suspend fun createNote(session: DeviceSession, title: String, body: String): Note = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("title", title).put("body", body)
        request(session.baseUrl, "POST", "/v1/notes", session.token, payload).toNote()
    }

    suspend fun updateNote(session: DeviceSession, note: Note): Note = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("title", note.title)
            .put("body", note.body)
            .put("color", note.color.name.lowercase())
            .put("pinned", note.pinned)
            .put("archived", note.archived)
        request(session.baseUrl, "PATCH", "/v1/notes/${note.id}", session.token, payload).toNote()
    }

    suspend fun sync(session: DeviceSession, cursor: Long): Pair<Long, List<Note>> = withContext(Dispatchers.IO) {
        val response = request(session.baseUrl, "GET", "/v1/sync?after=$cursor", session.token, null)
        val nextCursor = response.optLong("cursor", cursor)
        val changes = response.optJSONArray("changes") ?: return@withContext nextCursor to emptyList()
        val notes = buildList {
            for (index in 0 until changes.length()) {
                val change = changes.getJSONObject(index)
                change.optJSONObject("note")?.let { add(it.toNote()) }
            }
        }
        nextCursor to notes
    }

    private fun request(baseUrl: String, method: String, path: String, token: String?, body: JSONObject?): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json")
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IllegalStateException("Server returned $status: $text")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }
}

private fun JSONObject.toNote(): Note {
    return Note(
        id = getString("id"),
        title = optString("title"),
        body = optString("body"),
        color = runCatching { NoteColor.valueOf(optString("color", "default").uppercase()) }.getOrDefault(NoteColor.DEFAULT),
        pinned = optBoolean("pinned"),
        archived = optBoolean("archived"),
        trashed = optBoolean("trashed"),
        updatedAt = optLong("updatedAt"),
        photoCount = optInt("photoCount"),
        attachments = optJSONArray("attachments")?.let { attachments ->
            buildList {
                for (index in 0 until attachments.length()) {
                    val attachment = attachments.getJSONObject(index)
                    add(
                        app.wearnotes.model.Attachment(
                            id = attachment.getString("id"),
                            noteId = getString("id"),
                            uri = attachment.optString("url"),
                            width = 0,
                            height = 0,
                            mimeType = attachment.optString("mime"),
                        ),
                    )
                }
            }
        } ?: emptyList(),
    )
}
