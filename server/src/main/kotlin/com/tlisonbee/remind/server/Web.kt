package com.tlisonbee.remind.server

import com.tlisonbee.remind.shared.Limits
import com.tlisonbee.remind.shared.Recurrence
import com.tlisonbee.remind.shared.RepeatUnit
import com.tlisonbee.remind.shared.TaskDto
import com.tlisonbee.remind.shared.repeatLabel
import com.tlisonbee.remind.shared.repeatMenuLabel
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.auth.authenticate
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.html.FlowContent
import kotlinx.html.FormMethod
import kotlinx.html.HTML
import kotlinx.html.InputType
import kotlinx.html.UL
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.fieldSet
import kotlinx.html.form
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.legend
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.summary
import kotlinx.html.textArea
import kotlinx.html.title
import kotlinx.html.ul
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Plain server-rendered HTML forms: no JavaScript at all, which keeps the CSP strict.
 * Every change redirects back to a page (POST/redirect/GET).
 */
fun Route.webRoutes(config: Config, store: TaskStore) {
    get("/login") { call.respondHtml { loginPage(failed = false) } }
    rateLimit(LOGIN_RATE_LIMIT) {
        post("/login") {
            val token = call.receiveParameters()["token"].orEmpty().trim()
            if (constantTimeEquals(token, config.token)) {
                call.sessions.set(WebSession(System.currentTimeMillis() + SESSION_LIFETIME.inWholeMilliseconds))
                call.respondRedirect("/")
            } else {
                call.respondHtml(HttpStatusCode.Unauthorized) { loginPage(failed = true) }
            }
        }
    }
    post("/logout") {
        call.sessions.clear<WebSession>()
        call.respondRedirect("/login")
    }

    authenticate("web") {
        get("/") {
            val tasks = io { store.active() }
            call.respondHtml { listPage(tasks) }
        }
        post("/tasks") {
            val title = call.receiveParameters()["title"].orEmpty().trim().take(Limits.MAX_TITLE)
            if (title.isNotEmpty()) {
                val now = System.currentTimeMillis()
                io { store.save(TaskDto(id = UUID.randomUUID().toString(), title = title, createdAt = now, updatedAt = now)) }
            }
            call.respondRedirect("/")
        }
        get("/tasks/{id}") {
            val task = call.task(store) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respondHtml { editPage(task) }
        }
        post("/tasks/{id}") {
            val task = call.task(store) ?: return@post call.respond(HttpStatusCode.NotFound)
            val updated = task.withForm(call.receiveParameters())
            // Like the app: clearing out a task deletes it.
            val toSave = if (updated.title.isBlank() && updated.notes.isBlank()) updated.copy(deleted = true) else updated
            toSave.validate()
            io { store.save(toSave) }
            call.respondRedirect("/")
        }
        post("/tasks/{id}/toggle") {
            val task = call.task(store) ?: return@post call.respond(HttpStatusCode.NotFound)
            io { store.save(task.copy(done = !task.done)) }
            call.respondRedirect("/")
        }
        post("/tasks/{id}/delete") {
            val task = call.task(store) ?: return@post call.respond(HttpStatusCode.NotFound)
            io { store.save(task.copy(deleted = true)) }
            call.respondRedirect("/")
        }
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private suspend fun RoutingCall.task(store: TaskStore): TaskDto? {
    val id = parameters["id"] ?: return null
    return io { store.get(id) }?.takeUnless { it.deleted }
}

private fun TaskDto.withForm(form: Parameters): TaskDto {
    val remindAt = form["remindAt"]?.takeIf { it.isNotBlank() }?.let {
        try {
            LocalDateTime.parse(it).toString()
        } catch (_: DateTimeParseException) {
            throw BadRequestException("Invalid reminder time")
        }
    }
    val unit = if (remindAt == null) {
        RepeatUnit.NONE
    } else {
        RepeatUnit.entries.firstOrNull { it.name == form["repeatUnit"] } ?: RepeatUnit.NONE
    }
    return copy(
        // Browsers submit textarea line breaks as CRLF.
        title = form["title"].orEmpty().trim(),
        notes = form["notes"].orEmpty().replace("\r\n", "\n").trim(),
        remindAt = remindAt,
        repeatUnit = unit,
        repeatInterval = if (unit == RepeatUnit.NONE) {
            1
        } else {
            form["repeatInterval"]?.toIntOrNull()?.coerceIn(1, Limits.MAX_INTERVAL) ?: 1
        },
    )
}

// ---- Pages ----

private fun HTML.page(pageTitle: String, loggedIn: Boolean = true, content: FlowContent.() -> Unit) {
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title(pageTitle)
        link(rel = "stylesheet", href = "/static/style.css")
        link(rel = "icon", href = "/static/icon.svg")
    }
    body {
        header {
            a(href = "/", classes = "brand") { +"Remind" }
            if (loggedIn) {
                form(action = "/logout", method = FormMethod.post) {
                    button(classes = "link") { +"Log out" }
                }
            }
        }
        main { content() }
    }
}

private fun HTML.loginPage(failed: Boolean) = page("Log in · Remind", loggedIn = false) {
    form(action = "/login", method = FormMethod.post, classes = "card login") {
        label {
            +"Access token"
            input(type = InputType.password, name = "token") {
                required = true
                autoFocus = true
                attributes["autocomplete"] = "current-password"
            }
        }
        if (failed) p("error") { +"That token didn't match." }
        button(classes = "primary") { +"Log in" }
    }
}

private fun HTML.listPage(tasks: List<TaskDto>) = page("Remind") {
    val now = LocalDateTime.now()
    val withNext = tasks.map { it to nextOccurrence(it, now) }
    // Same order as the app: upcoming reminders first (soonest at the top), then by last edit.
    val active = withNext.filter { !it.first.done }.sortedWith(
        compareBy<Pair<TaskDto, LocalDateTime?>, LocalDateTime?>(nullsLast()) { it.second }
            .thenByDescending { it.first.updatedAt },
    )
    val completed = withNext.filter { it.first.done }.sortedByDescending { it.first.updatedAt }

    form(action = "/tasks", method = FormMethod.post, classes = "add") {
        input(type = InputType.text, name = "title") {
            placeholder = "Add a task"
            required = true
            autoFocus = true
            maxLength = Limits.MAX_TITLE.toString()
        }
        button(classes = "primary") { +"Add" }
    }
    if (tasks.isEmpty()) p("empty") { +"No tasks yet." }
    ul("tasks") { active.forEach { (task, next) -> taskItem(task, next) } }
    if (completed.isNotEmpty()) {
        details {
            summary { +"Completed (${completed.size})" }
            ul("tasks") { completed.forEach { (task, next) -> taskItem(task, next) } }
        }
    }
}

private fun UL.taskItem(task: TaskDto, next: LocalDateTime?) {
    li(if (task.done) "task done" else "task") {
        form(action = "/tasks/${task.id}/toggle", method = FormMethod.post) {
            button(classes = "check") {
                attributes["aria-label"] = if (task.done) "Mark not done" else "Mark done"
                attributes["title"] = if (task.done) "Mark not done" else "Mark done"
                if (task.done) +"✓"
            }
        }
        a(href = "/tasks/${task.id}", classes = "body") {
            span("title") { +task.title.ifBlank { "(untitled)" } }
            if (task.notes.isNotBlank()) span("notes") { +task.notes }
            reminderSummary(task, next)?.let { summary ->
                span(if (next != null && !task.done) "reminder" else "reminder past") { +"🔔 $summary" }
            }
        }
    }
}

private fun HTML.editPage(task: TaskDto) = page("${task.title.ifBlank { "Task" }} · Remind") {
    form(action = "/tasks/${task.id}", method = FormMethod.post, classes = "card edit") {
        label {
            +"Title"
            input(type = InputType.text, name = "title") {
                value = task.title
                maxLength = Limits.MAX_TITLE.toString()
            }
        }
        label {
            +"Notes"
            textArea(rows = "6") {
                name = "notes"
                +task.notes
            }
        }
        fieldSet {
            legend { +"Reminder" }
            label {
                +"First reminder (leave empty for none)"
                input(type = InputType.dateTimeLocal, name = "remindAt") {
                    value = task.remindAt.orEmpty()
                }
            }
            div("row") {
                label {
                    +"Repeat"
                    select {
                        name = "repeatUnit"
                        RepeatUnit.entries.forEach { unit ->
                            option {
                                value = unit.name
                                selected = unit == task.repeatUnit
                                +repeatMenuLabel(unit)
                            }
                        }
                    }
                }
                label {
                    +"Every"
                    input(type = InputType.number, name = "repeatInterval") {
                        min = "1"
                        max = Limits.MAX_INTERVAL.toString()
                        value = task.repeatInterval.toString()
                    }
                }
            }
            nextOccurrence(task, LocalDateTime.now())?.let { p("hint") { +"Next: ${formatDateTime(it)}" } }
        }
        div("actions") {
            button(classes = "primary") { +"Save" }
            a(href = "/", classes = "button") { +"Cancel" }
        }
    }
    form(action = "/tasks/${task.id}/delete", method = FormMethod.post, classes = "delete") {
        button(classes = "danger") { +"Delete task" }
    }
}

// ---- Formatting ----

private fun nextOccurrence(task: TaskDto, now: LocalDateTime): LocalDateTime? {
    if (task.done) return null
    val anchor = task.remindAt?.let(LocalDateTime::parse) ?: return null
    return Recurrence.firstAtOrAfter(anchor, task.repeatUnit, task.repeatInterval, now.truncatedTo(ChronoUnit.MINUTES))
}

private fun reminderSummary(task: TaskDto, next: LocalDateTime?): String? {
    val time = next ?: task.remindAt?.let(LocalDateTime::parse) ?: return null
    val repeat = if (task.repeatUnit != RepeatUnit.NONE) " · ${repeatLabel(task.repeatUnit, task.repeatInterval)}" else ""
    return formatDateTime(time) + repeat
}

private val TIME = DateTimeFormatter.ofPattern("h:mm a")

private fun formatDateTime(dateTime: LocalDateTime): String {
    val date = dateTime.toLocalDate()
    val today = LocalDate.now()
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy"))
    }
    return "$day, ${dateTime.format(TIME)}"
}
