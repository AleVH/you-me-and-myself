// ==========================
// File: build.gradle.kts
// ==========================
// (project-root)/build.gradle.kts — Gradle config for the plugin (migrated to 2.x)

plugins {
    // Kotlin (unchanged)
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    // IntelliJ Platform Gradle Plugin 2.x (latest)
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

repositories {
    mavenCentral()
    // Required by 2.x plugin
//    intellijPlatform { defaultRepositories() }
    intellijPlatform.defaultRepositories()
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

    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // IntelliJ Platform target (parameterized)
    intellijPlatform {
        val type    = providers.gradleProperty("platformType").orElse("IC")
        val version = providers.gradleProperty("platformVersion").orElse("2024.3.2")

        create(type, version)

        // Only IntelliJ IDEA bundles these; PhpStorm/WebStorm do not.
        when (type.get()) {
            "IC", "IU" -> bundledPlugins("com.intellij.java") //, "com.intellij.gradle")
            else       -> { /* no IDEA-only plugins when running on PhpStorm, etc. */ }
        }
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
    runIde {
        // Disable Gradle plugin in the sandbox no matter what
        jvmArgs("-Didea.plugins.disabled=com.intellij.gradle", "-Dymm.devMode=true")

        // (Optional) print sandbox for log inspection
        doFirst {
            val sandbox = layout.buildDirectory.dir("idea-sandbox").get().asFile.absolutePath
            println("Sandbox dir: $sandbox")

            // Clear plugin profiles before each run
            val configFile = File(sandbox, "config/options/youmeandmyself-ai-profiles.xml")
            if (configFile.exists()) {
                configFile.delete()
                println("DELETED persistent profiles: ${configFile.absolutePath}")
            } else {
                println("No profiles file found at: ${configFile.absolutePath}")
            }
        }
    }
}
