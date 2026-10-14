import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

version = "0.1.0"

// Runs on any Java 17+ runtime, not just the JDK it was built with.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

application {
    mainClass = "com.tlisonbee.remind.desktop.MainKt"
    applicationName = "remind-desktop"
    // A small, always-running background process: keep the JVM lean.
    applicationDefaultJvmArgs = listOf(
        "-Xmx48m",
        "-Xss512k",
        "-XX:+UseSerialGC",
        "-XX:TieredStopAtLevel=1",
        "-XX:ReservedCodeCacheSize=16m",
        "-XX:-UsePerfData",
        "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn",
    )
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.unixsocket)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnit() }

distributions {
    main {
        contents {
            // Same icon as the web UI.
            from("../server/src/main/resources/static/icon.svg") {
                into("share")
                rename { "remind.svg" }
            }
        }
    }
}

// ---- Self-contained package: app + a trimmed Java runtime, so target machines need no Java ----

// From `jdeps --print-module-deps`, plus jdk.crypto.ec, which TLS needs for the ECDSA certificates
// that Tailscale/Let's Encrypt issue (jdeps can't see that).
val runtimeModules = listOf(
    "java.base", "java.instrument", "java.logging", "java.net.http", "java.xml",
    "jdk.crypto.ec", "jdk.security.auth", "jdk.unsupported",
)

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    description = "Builds a trimmed Java runtime for the bundled package."
    val output = layout.buildDirectory.dir("jlink/runtime").get().asFile
    inputs.property("modules", runtimeModules)
    outputs.dir(output)
    // jlink from the JDK running Gradle; the runtime matches this machine's OS and CPU.
    val jlink = File(System.getProperty("java.home"), "bin/jlink")
    doFirst { output.deleteRecursively() } // jlink refuses to overwrite
    commandLine(
        jlink, "--add-modules", runtimeModules.joinToString(","),
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
        "--output", output,
    )
}

// The launcher uses the bundled runtime when there is one, otherwise JAVA_HOME / java on PATH.
tasks.startScripts {
    doLast {
        val script = unixScript
        val text = script.readText()
        val anchor = Regex("""(?m)^APP_HOME=.*\n""").find(text)
            ?: error("Start script layout changed; update the bundled-runtime patch")
        val useBundled = "[ -x \"\$APP_HOME/runtime/bin/java\" ] && JAVA_HOME=\"\$APP_HOME/runtime\"\n"
        script.writeText(text.substring(0, anchor.range.last + 1) + useBundled + text.substring(anchor.range.last + 1))
    }
}

tasks.register<Tar>("bundledDist") {
    description = "Self-contained tarball for installing on other Linux machines (x86_64)."
    group = "distribution"
    val arch = System.getProperty("os.arch").let { if (it == "amd64") "x86_64" else it }
    val baseName = "remind-desktop-${project.version}-linux-$arch"
    archiveFileName = "$baseName.tar.gz"
    compression = Compression.GZIP
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into(baseName) {
        with(distributions["main"].contents)
        into("runtime") { from(jlinkRuntime) }
    }
    // Archive tasks don't keep the executable bit from the source files, so set it explicitly.
    val executable = Regex(".*/(install\\.sh|bin/[^/]+|runtime/lib/jspawnhelper)")
    inputs.property("executablePattern", executable.pattern) // eachFile isn't tracked on its own
    eachFile {
        if (executable.matches(path)) permissions { unix("rwxr-xr-x") }
    }
}
