package com.tlisonbee.remind.server

import com.tlisonbee.remind.shared.SyncRequest
import com.tlisonbee.remind.shared.SyncResponse
import com.tlisonbee.remind.shared.TaskDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private val token = "test-token-that-is-at-least-32-characters-long"

    private fun withServer(block: suspend ApplicationTestBuilder.(TaskStore) -> Unit) {
        val db = File.createTempFile("remind-test", ".db")
        val store = TaskStore(db.path)
        try {
            testApplication {
                application { remindModule(Config("127.0.0.1", 0, db.path, token), store) }
                block(store)
            }
        } finally {
            store.close()
            db.delete()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json() }
    }

    private fun ApplicationTestBuilder.browser() = createClient {
        followRedirects = false
        install(HttpCookies)
    }

    private fun task(title: String, updatedAt: Long, id: String = UUID.randomUUID().toString()) =
        TaskDto(id = id, title = title, createdAt = 1, updatedAt = updatedAt)

    private suspend fun HttpClient.sync(request: SyncRequest, auth: String? = token) = post("/api/sync") {
        auth?.let { bearerAuth(it) }
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    @Test
    fun apiRequiresToken() = withServer {
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.sync(SyncRequest(0, emptyList()), auth = null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.sync(SyncRequest(0, emptyList()), auth = "wrong").status)
    }

    @Test
    fun syncPushesPullsAndKeepsNewestEdit() = withServer {
        val client = jsonClient()
        val a = task("A", updatedAt = 100)
        val first = client.sync(SyncRequest(0, listOf(a))).body<SyncResponse>()
        assertEquals(listOf(a), first.changes)

        // An older edit of the same task loses; a newer one wins and moves the cursor.
        val stale = client.sync(SyncRequest(first.cursor, listOf(a.copy(title = "old", updatedAt = 50)))).body<SyncResponse>()
        assertEquals(emptyList(), stale.changes)
        assertEquals(first.cursor, stale.cursor)

        val newer = a.copy(title = "A2", updatedAt = 200)
        val second = client.sync(SyncRequest(first.cursor, listOf(newer))).body<SyncResponse>()
        assertEquals(listOf(newer), second.changes)
        assertTrue(second.cursor > first.cursor)

        // Another device starting from scratch sees only the latest version.
        assertEquals(listOf(newer), client.sync(SyncRequest(0, emptyList())).body<SyncResponse>().changes)
    }

    @Test
    fun syncRejectsInvalidTasks() = withServer {
        val client = jsonClient()
        val bad = task("x", 1).copy(remindAt = "tomorrow-ish")
        assertEquals(HttpStatusCode.BadRequest, client.sync(SyncRequest(0, listOf(bad))).status)
        assertEquals(HttpStatusCode.BadRequest, client.sync(SyncRequest(0, listOf(task("x", 1, id = "not-a-uuid")))).status)
    }

    @Test
    fun webRequiresLogin() = withServer {
        val client = browser()
        val response = client.get("/")
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals("/login", response.headers[HttpHeaders.Location])

        assertEquals(HttpStatusCode.Unauthorized, client.submitForm("/login", parameters { append("token", "nope") }).status)
        assertEquals(HttpStatusCode.Found, client.get("/").status)
    }

    @Test
    fun webEditsAreVisibleToSyncAndEscaped() = withServer { store ->
        val client = browser()
        client.submitForm("/login", parameters { append("token", token) })
        assertEquals(HttpStatusCode.Found, client.submitForm("/tasks", parameters { append("title", "<script>x</script>") }).status)

        val page = client.get("/").bodyAsText()
        assertTrue("&lt;script&gt;" in page)
        assertFalse("<script>x" in page)

        val created = store.active().single()
        client.submitForm(
            "/tasks/${created.id}",
            parameters {
                append("title", "Water plants")
                append("notes", "line 1\r\nline 2")
                append("remindAt", "2030-01-31T09:00")
                append("repeatUnit", "MONTH")
                append("repeatInterval", "2")
            },
        )
        val synced = jsonClient().sync(SyncRequest(0, emptyList())).body<SyncResponse>().changes.single()
        assertEquals("Water plants", synced.title)
        assertEquals("line 1\nline 2", synced.notes)
        assertEquals("2030-01-31T09:00", synced.remindAt)
        assertEquals(2, synced.repeatInterval)
        assertTrue(synced.updatedAt > created.updatedAt)
    }
}
