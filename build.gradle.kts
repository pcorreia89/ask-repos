plugins {
    kotlin("jvm") version "2.2.21"
    // kotlinx.serialization: needed for Anthropic/Voyage JSON request+response shapes.
    // Writing a JSON parser by hand is not minimalism, it's masochism.
    kotlin("plugin.serialization") version "2.2.21"
    application
}

group = "askrepo"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Only hard dependency: kotlinx.serialization for JSON (justified above).
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Slack Bolt SDK: writing Socket Mode + event handling from scratch would be
    // a significant undertaking. This pulls in slack-api-client and model types.
    implementation("com.slack.api:bolt-socket-mode:1.44.2")
    // WebSocket implementation required by Slack Socket Mode.
    implementation("javax.websocket:javax.websocket-api:1.1")
    implementation("org.glassfish.tyrus.bundles:tyrus-standalone-client:1.20")
    // SLF4J binding — Bolt uses SLF4J internally; route to JDK logging.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // Tests: stdlib kotlin.test only, no JUnit extras, no MockK.
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("askrepo.MainKt")
    applicationName = "ask-repos"
}

tasks.test {
    useJUnitPlatform()
}

// Silence: application plugin's default 64k args length on zsh can truncate quoted questions.
tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
