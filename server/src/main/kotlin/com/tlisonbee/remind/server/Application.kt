package com.tlisonbee.remind.server

import com.tlisonbee.remind.shared.SyncRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.session
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** Signed (not encrypted) browser session; it holds nothing secret, only an expiry. */
@Serializable
data class WebSession(val expiresAt: Long)

val SESSION_LIFETIME = 30.days
val LOGIN_RATE_LIMIT = RateLimitName("login")

fun Application.remindModule(config: Config, store: TaskStore) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<BadRequestException> { call, e ->
            call.respondText(e.message ?: "Bad request", status = HttpStatusCode.BadRequest)
        }
    }
    install(SecurityHeaders)
    install(RateLimit) {
        register(LOGIN_RATE_LIMIT) { rateLimiter(limit = 10, refillPeriod = 1.minutes) }
    }
    install(Sessions) {
        cookie<WebSession>("remind_session") {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.maxAgeInSeconds = SESSION_LIFETIME.inWholeSeconds
            // Strict keeps the cookie off cross-site requests, which is our CSRF protection. It is
            // not marked Secure because the server itself only speaks plain HTTP on loopback; the
            // browser reaches it over HTTPS via `tailscale serve`.
            cookie.extensions["SameSite"] = "Strict"
            // Keyed off the token, so rotating the token also logs out every browser.
            transform(SessionTransportTransformerMessageAuthentication(sha256("remind-session:${config.token}")))
        }
    }
    install(Authentication) {
        bearer("api") {
            authenticate { credential ->
                if (constantTimeEquals(credential.token, config.token)) UserIdPrincipal("owner") else null
            }
        }
        session<WebSession>("web") {
            validate { session -> session.takeIf { it.expiresAt > System.currentTimeMillis() } }
            challenge { call.respondRedirect("/login") }
        }
    }

    routing {
        staticResources("/static", "static")
        authenticate("api") { apiRoutes(store) }
        webRoutes(config, store)
    }
}

private fun Route.apiRoutes(store: TaskStore) {
    post("/api/sync") {
        val request = call.receive<SyncRequest>()
        request.validate()
        call.respond(withContext(Dispatchers.IO) { store.sync(request) })
    }
}

private val SecurityHeaders = createApplicationPlugin("SecurityHeaders") {
    onCall { call ->
        with(call.response.headers) {
            append(
                "Content-Security-Policy",
                "default-src 'none'; style-src 'self'; img-src 'self'; form-action 'self'; " +
                    "frame-ancestors 'none'; base-uri 'none'",
            )
            append("X-Content-Type-Options", "nosniff")
            append("Referrer-Policy", "no-referrer")
        }
    }
}

fun constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
