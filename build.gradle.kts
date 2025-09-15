// ==========================
// File: build.gradle.kts
// ==========================
// (project-root)/build.gradle.kts — Gradle config for the plugin (migrated to 2.x)

plugins {
    // Kotlin (unchanged)
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    // IntelliJ Platform Gradle Plugin 2.x (latest)
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

repositories {
    mavenCentral()
    // Required by 2.x plugin
    intellijPlatform { defaultRepositories() }
}

dependencies {
    // Ktor client for simple HTTP calls to providers (unchanged)
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-cio:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("io.ktor:ktor-client-logging:2.3.13")
    // Kotlinx JSON (match Ktor 2.3.x with 1.6.x)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // IntelliJ Platform target (matches your previous baseline: IC 2024.3 / 243)
    intellijPlatform {
        create("IC", "2024.3")
        bundledPlugins("com.intellij.java", "com.intellij.gradle")
    }
}

// Java/Kotlin toolchains pinned to 17 to satisfy the 243 line requirements
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}
kotlin {
    jvmToolchain(17)
}

// Plugin metadata (sinceBuild) in the 2.x location
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("243")
            // untilBuild left unset
        }
    }
}

tasks {
    // Default runIde; let the plugin/IDE pick the right runtime (no JBR overrides)
    runIde { }
}
