package com.tlisonbee.remind.desktop

import com.tlisonbee.remind.shared.SyncJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** XDG locations: config in ~/.config/remind, task cache in ~/.local/state/remind. */
object Paths {
    private val home = System.getProperty("user.home")
    private val configDir = Path(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "remind")
    private val stateDir = Path(System.getenv("XDG_STATE_HOME") ?: "$home/.local/state", "remind")

    val config: Path = configDir.resolve("desktop.properties")
    val token: Path = configDir.resolve("token")
    val state: Path = stateDir.resolve("desktop-state.json")
}

data class DesktopConfig(val serverUrl: String, val token: String) {
    companion object {
        fun load(): DesktopConfig? {
            if (!Paths.config.exists() || !Paths.token.exists()) return null
            val props = Properties().apply { Files.newBufferedReader(Paths.config).use(::load) }
            val url = props.getProperty("serverUrl") ?: return null
            return DesktopConfig(url, Paths.token.readText().trim())
        }

        fun save(config: DesktopConfig) {
            writePrivate(Paths.config, "serverUrl=${config.serverUrl}\n")
            writePrivate(Paths.token, config.token + "\n")
        }
    }
}

class StateStore(private val path: Path = Paths.state) {
    fun load(): DesktopState =
        if (path.exists()) SyncJson.decodeFromString(DesktopState.serializer(), path.readText()) else DesktopState()

    fun save(state: DesktopState) = writePrivate(path, SyncJson.encodeToString(DesktopState.serializer(), state))

    fun clear() = Files.deleteIfExists(path)
}

/** Writes atomically (temp file + rename), readable only by the owner. */
fun writePrivate(path: Path, content: String) {
    Files.createDirectories(path.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val tmp = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
    try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        Files.writeString(tmp, content)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(tmp)
    }
}
