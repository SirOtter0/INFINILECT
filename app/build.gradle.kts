plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm("desktop")
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
            implementation("org.jetbrains.compose.material:material:1.12.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.ktor:ktor-client-core:3.6.0")
                implementation("io.ktor:ktor-client-java:3.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        getByName("desktopTest").dependencies {
            implementation("io.ktor:ktor-client-mock:3.6.0")
        }
    }
}

// Opt-in live check: never a dependency of test/check/build.
tasks.register<JavaExec>("gutenbergSearchCheck") {
    group = "verification"
    description = "Request one real Gutenberg OPDS search page (no graphical UI)."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.infinilect.app.gutenberg.GutenbergIntegrationCheck")
}

// Opt-in access diagnostic, independent of Gutenberg and of test/check/build.
tasks.register<JavaExec>("oapenApiAccessCheck") {
    group = "verification"
    description = "Inspect one bounded response from the documented OAPEN REST endpoint (no acquisition)."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.infinilect.app.oapen.OapenApiAccessCheck")
}

// Explicit experiments only; these tasks are never dependencies of test/check/build.
mapOf(
    "oapenAlternateAccessCheck" to "org.infinilect.app.oapen.OapenAlternateAccessCheck",
    "internetArchiveAcquisitionCheck" to "org.infinilect.app.archive.InternetArchiveAcquisitionCheck",
    "internetArchiveTextReadingCheck" to "org.infinilect.app.archive.InternetArchiveTextReadingCheck",
).forEach { (taskName, entrypoint) ->
    tasks.register<JavaExec>(taskName) {
        group = "verification"
        description = "Run one bounded, opt-in official source acquisition experiment."
        dependsOn("desktopTestClasses")
        val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
        classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
        mainClass.set(entrypoint)
    }
}

compose.desktop {
    application {
        mainClass = "org.infinilect.app.MainKt"
    }
}
