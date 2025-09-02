// ==========================
// File: build.gradle.kts
// ==========================
// (project-root)/build.gradle.kts — Gradle config for the plugin
plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    id("org.jetbrains.intellij") version "1.17.2"
}

repositories { mavenCentral() }

dependencies {
    // Ktor client for simple HTTP calls to providers
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    // Kotlinx JSON (match Ktor 2.3.x with 1.6.x)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellij {
    // Align sandbox IDE to 2024.3 (build line 243.*) to avoid Gradle-on-Java-25 crash
    version.set("2024.3")
    plugins.set(listOf("com.intellij.java", "gradle"))
}

tasks {
    patchPluginXml {
        // Make the plugin compatible with all 243.* builds (2024.3.*)
        sinceBuild.set("243")
        untilBuild.set(null as String?)
    }
    runIde {
        // Force the sandbox to run on JBR 21 (stable); avoids Java 25 parsing crash
        jbrVersion.set("21.0.3-b505.1")
        // Force the sandbox process to use JDK 21, not 25
        // macOS typical paths (pick the one you actually have installed):
        //   Temurin:     /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
        //   JetBrains:   ~/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home
        //   IntelliJ JDK downloader (IDE-managed):  ~/.jdks/<downloaded-21-name>
        environment("JAVA_HOME", "/Users/alejandrovanhoutte/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home")
        environment("JDK_HOME",  "/Users/alejandrovanhoutte/Library/Java/JavaVirtualMachines/jbr-21.0.8/Contents/Home")
    }
}
