import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

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
