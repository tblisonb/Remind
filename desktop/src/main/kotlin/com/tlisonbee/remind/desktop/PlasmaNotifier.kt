package com.tlisonbee.remind.desktop

import com.tlisonbee.remind.shared.TaskDto
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusMemberName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap

/** The freedesktop notification spec, which Plasma implements. */
@DBusInterfaceName("org.freedesktop.Notifications")
interface Notifications : DBusInterface {
    // Not named `notify`: dbus-java treats that name as Object.notify() and never sends the call.
    @DBusMemberName("Notify")
    fun sendNotification(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: Array<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int,
    ): UInt32

    @DBusMemberName("CloseNotification")
    fun closeNotification(id: UInt32)

    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)

    class NotificationClosed(path: String, val id: UInt32, val reason: UInt32) : DBusSignal(path, id, reason)
}

object Actions {
    const val OPEN = "default" // clicking the notification itself
    const val DONE = "done"
    const val SNOOZE = "snooze"
}

/**
 * Shows reminders as native Plasma notifications, one per task (re-showing a task replaces its
 * previous notification), and reports button clicks back as (taskId, action).
 */
class PlasmaNotifier(private val onAction: (taskId: String, action: String) -> Unit) : Notifier, AutoCloseable {
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().build()
    private val service = connection.getRemoteObject(
        "org.freedesktop.Notifications",
        "/org/freedesktop/Notifications",
        Notifications::class.java,
    )
    private val byTask = ConcurrentHashMap<String, UInt32>()
    private val byNotification = ConcurrentHashMap<UInt32, String>()

    init {
        // These signals are broadcast for every app's notifications; only ours are in the maps.
        connection.addSigHandler(Notifications.ActionInvoked::class.java) { signal ->
            byNotification[signal.id]?.let { taskId -> onAction(taskId, signal.actionKey) }
        }
        connection.addSigHandler(Notifications.NotificationClosed::class.java) { signal ->
            byNotification.remove(signal.id)?.let { taskId -> byTask.remove(taskId, signal.id) }
        }
    }

    /** Returns Plasma's id for the notification. */
    fun showAndGetId(task: TaskDto): UInt32 {
        show(task)
        return byTask.getValue(task.id)
    }

    override fun show(task: TaskDto) {
        val id = service.sendNotification(
            "Remind",
            byTask[task.id] ?: UInt32(0),
            "remind",
            task.title.ifBlank { "Reminder" },
            escapeMarkup(task.notes),
            arrayOf(Actions.OPEN, "Open", Actions.DONE, "Done", Actions.SNOOZE, "Snooze 1h"),
            mapOf(
                // Groups our notifications under the Remind app in Plasma's notification settings.
                "desktop-entry" to Variant("remind"),
                "urgency" to Variant(1.toByte()),
            ),
            // Stay on screen until acted on: once a popup times out, Plasma reports it closed
            // and its buttons stop reaching us.
            0,
        )
        byTask.put(task.id, id)?.let { old -> if (old != id) byNotification.remove(old) }
        byNotification[id] = task.id
    }

    override fun close(taskId: String) {
        val id = byTask.remove(taskId) ?: return
        byNotification.remove(id)
        runCatching { service.closeNotification(id) }
    }

    override fun close() = connection.close()

    /** Plasma renders a subset of HTML in the body, so notes must not be interpreted as markup. */
    private fun escapeMarkup(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
