package com.tlisonbee.remind.desktop

import com.tlisonbee.remind.shared.SyncRequest
import com.tlisonbee.remind.shared.TaskDto
import com.tlisonbee.remind.shared.normalizeServerUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

private const val USAGE = """Usage: remind-desktop <command>

  setup               Connect to your Remind server (asks for the URL and access token)
  run                 Run the reminder service (systemd starts this for you)
  status              Show the server, task count and upcoming reminders
  open [task-id]      Open the web UI (or one task) in the browser
  test-notification   Show a sample notification and report which button you click
"""

private const val SNOOZE_MINUTES = 60L
private val SYNC_INTERVAL = 1.minutes

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "setup" -> setup()
        "run" -> run()
        "status" -> status()
        "open" -> open(args.getOrNull(1))
        "test-notification" -> testNotification()
        else -> {
            print(USAGE)
            exitProcess(if (args.isEmpty() || args[0] in listOf("-h", "--help", "help")) 0 else 2)
        }
    }
}

private fun requireConfig(): DesktopConfig = DesktopConfig.load() ?: run {
    System.err.println("Not set up yet; run `remind-desktop setup` first.")
    exitProcess(1)
}

private fun setup() {
    val console = System.console()
    fun ask(prompt: String, secret: Boolean = false): String {
        print(prompt)
        val answer = when {
            console != null && secret -> console.readPassword()?.let(::String)
            else -> readlnOrNull()
        }
        return answer.orEmpty().trim()
    }

    val url = normalizeServerUrl(ask("Server URL (e.g. https://my-pc.tailnet.ts.net): "))
        ?: run {
            System.err.println("That isn't a valid https:// URL.")
            exitProcess(1)
        }
    val token = ask("Access token (input hidden): ", secret = true)
    if (token.isEmpty()) exitProcess(1)

    val config = DesktopConfig(url, token)
    print("Checking the connection... ")
    try {
        SyncClient(config).sync(SyncRequest(since = 0, changes = emptyList()))
    } catch (e: Exception) {
        println("failed.\n${e.message}")
        exitProcess(1)
    }
    println("OK.")

    val previous = DesktopConfig.load()
    DesktopConfig.save(config)
    // A different server means a different set of tasks: start the local copy from scratch.
    if (previous?.serverUrl != url) StateStore().clear()
    println("Saved. Start (or restart) the service with: systemctl --user restart remind-desktop")
}

private fun run() = runBlocking {
    val config = requireConfig()
    val store = StateStore()
    val wakeScheduler = Channel<Unit>(Channel.CONFLATED)
    val syncNow = Channel<Unit>(Channel.CONFLATED)

    lateinit var engine: ReminderEngine
    val notifier = PlasmaNotifier { taskId, action ->
        when (action) {
            Actions.DONE -> engine.done(taskId).also { syncNow.trySend(Unit) }
            Actions.SNOOZE -> engine.snooze(taskId, SNOOZE_MINUTES).also { wakeScheduler.trySend(Unit) }
            Actions.OPEN -> openInBrowser(config, taskId)
        }
    }
    engine = ReminderEngine(store.load(), notifier, onChange = store::save)
    println("Remind desktop started; syncing with ${config.serverUrl}")

    // Sync every minute, and right after a local change.
    launch {
        val client = SyncClient(config)
        var lastError: String? = null
        while (true) {
            try {
                val pushed = engine.pending()
                val response = withContext(Dispatchers.IO) {
                    client.sync(SyncRequest(engine.cursor, pushed))
                }
                engine.applyRemote(response.changes, response.cursor)
                engine.markSynced(pushed)
                wakeScheduler.trySend(Unit)
                if (lastError != null) println("Sync working again.")
                lastError = null
            } catch (e: Exception) {
                // Log each distinct failure once instead of every minute.
                val message = e.message ?: e.javaClass.simpleName
                if (message != lastError) System.err.println("Sync failed: $message")
                lastError = message
            }
            withTimeoutOrNull(SYNC_INTERVAL) { syncNow.receive() }
        }
    }

    // Fire due reminders. Waking at least every 30 s also catches up after suspend/resume.
    launch {
        while (true) {
            engine.fireDue()
            val wait = engine.timeUntilNextDue()?.toKotlinDuration()?.coerceIn(1.seconds, 30.seconds) ?: 30.seconds
            withTimeoutOrNull(wait) { wakeScheduler.receive() }
        }
    }
}

private fun status() {
    val config = requireConfig()
    val active = StateStore().load().tasks.values.filter { !it.task.deleted && !it.task.done }
    println("Server: ${config.serverUrl}")
    println("Active tasks: ${active.size}")
    val upcoming = active.filter { it.nextReminderAt != null }.sortedBy { LocalDateTime.parse(it.nextReminderAt) }
    if (upcoming.isEmpty()) println("No upcoming reminders.")
    upcoming.take(10).forEach { println("  ${it.nextReminderAt!!.replace('T', ' ')}  ${it.task.title}") }
}

private fun open(taskId: String?) {
    val config = requireConfig()
    openInBrowser(config, taskId)
}

private fun openInBrowser(config: DesktopConfig, taskId: String?) {
    val url = config.serverUrl + (taskId?.let { "/tasks/$it" } ?: "/")
    // Launch through the user's systemd manager so the browser doesn't inherit this service's
    // sandbox (read-only home); fall back to a direct launch outside systemd.
    val viaSystemd = runCatching {
        ProcessBuilder("systemd-run", "--user", "--quiet", "--collect", "xdg-open", url).inheritIO().start().waitFor() == 0
    }.getOrDefault(false)
    if (!viaSystemd) ProcessBuilder("xdg-open", url).inheritIO().start()
}

private fun testNotification() {
    val clicked = CountDownLatch(1)
    PlasmaNotifier { _, action ->
        println("You clicked: $action")
        clicked.countDown()
    }.use { notifier ->
        val now = System.currentTimeMillis()
        val id = notifier.showAndGetId(
            TaskDto(
                id = UUID.randomUUID().toString(),
                title = "Remind test notification",
                notes = "Click a button (or the notification) to check that actions work.",
                createdAt = now,
                updatedAt = now,
            ),
        )
        println("Notification $id shown; waiting up to 60 s for a click...")
        if (!clicked.await(60, TimeUnit.SECONDS)) println("No click received.")
    }
}
