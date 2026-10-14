package com.tlisonbee.remind.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.File

data class Config(
    val host: String,
    val port: Int,
    val dbPath: String,
    val token: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val token = env["REMIND_TOKEN_FILE"]?.let { File(it).readText().trim() }
                ?: env["REMIND_TOKEN"]
                ?: error("Set REMIND_TOKEN_FILE (preferred) or REMIND_TOKEN")
            require(token.length >= 32) {
                "The token must be at least 32 characters; generate one with: openssl rand -base64 32"
            }
            return Config(
                // Loopback by default: only reachable through a local proxy such as `tailscale serve`.
                host = env["REMIND_HOST"] ?: "127.0.0.1",
                port = env["REMIND_PORT"]?.toInt() ?: 8765,
                dbPath = env["REMIND_DB"] ?: "remind.db",
                token = token,
            )
        }
    }
}

fun main() {
    val config = Config.fromEnv()
    val store = TaskStore(config.dbPath)
    embeddedServer(Netty, host = config.host, port = config.port) {
        remindModule(config, store)
    }.start(wait = true)
}
