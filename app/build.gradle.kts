import java.io.File
import java.security.MessageDigest
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
}

// Compiler diagnostics only: no runtime counters or dependency in normal builds.
composeCompiler {
    if (providers.gradleProperty("infinilect.composeReports").orNull == "true") {
        reportsDestination.set(layout.buildDirectory.dir("compose-diagnostics"))
        metricsDestination.set(layout.buildDirectory.dir("compose-diagnostics"))
    }
}

// Pinned, checksummed upstream SDK; generated AAR stays under build/ and is never tracked.
// Corresponding MPL-covered source: Foxlet commit 0eff2a15032362115848a496ce1df8ed9fc68650.
val foxletArtifact = layout.buildDirectory.file("translation-sdk/foxlet-v0.5.0.aar")
val prepareEpubTranslationSdk = tasks.register("prepareEpubTranslationSdk") {
    val localSdk = providers.gradleProperty("infinilect.foxletSdk")
    inputs.property("localSdk", localSdk.orElse(""))
    outputs.file(foxletArtifact)
    doLast {
        val output = foxletArtifact.get().asFile
        output.parentFile.mkdirs()
        val expected = "5dbb384907d69c2aba129fd15dba30dda94ede0fa556dc6a167d041ee479ec0e"
        fun verified(file: File) = file.isFile && MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) } == expected
        if (!verified(output)) {
            val provided = localSdk.orNull?.let { File(it) }
            if (provided != null) {
                check(verified(provided)) { "Provided Foxlet SDK checksum mismatch." }
                provided.copyTo(output, overwrite = true)
            } else {
            check(!gradle.startParameter.isOffline) { "Foxlet SDK is not cached. Run prepareEpubTranslationSdk online once, or supply -Pinfinilect.foxletSdk=/path/to/verified/foxlet-v0.5.0.aar." }
            val temporary = File(output.parentFile, "foxlet.part")
            try {
                val connection = URI("https://github.com/yinvoke/foxlet-translate/releases/download/v0.5.0/foxlet-v0.5.0.aar").toURL().openConnection()
                connection.connectTimeout = 30_000; connection.readTimeout = 30_000
                connection.getInputStream().use { input -> temporary.outputStream().use { out ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) { val read = input.read(buffer); if (read < 0) break; total += read; check(total <= 3_000_000); out.write(buffer, 0, read) }
                } }
                check(verified(temporary)) { "Foxlet SDK checksum mismatch." }
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
            }
        }
        check(verified(output))
    }
}


kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    android {
        namespace = "org.infinilect.shared"
        compileSdk = 37
        minSdk = 26
        // AGP KMP libraries default to no Android resources/assets.
        androidResources { enable = true }
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        withHostTest {}
    }
    jvmToolchain(21)
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
            implementation("org.jetbrains.compose.material:material:1.12.1")
            implementation("org.jetbrains.compose.components:components-resources:1.12.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("app.cash.sqldelight:runtime:2.4.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(commonMain.get())
            dependencies {
                implementation("io.ktor:ktor-client-core:3.6.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        getByName("desktopMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("io.ktor:ktor-client-java:3.6.0")
                // Retained session ownership uses Main independently of a Compose scene.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
                implementation("app.cash.sqldelight:sqlite-driver:2.4.0")
                implementation("org.apache.pdfbox:pdfbox:3.0.8")
            }
        }
        getByName("androidMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.6.0")
                implementation(files(foxletArtifact).builtBy(prepareEpubTranslationSdk))
                implementation("app.cash.sqldelight:android-driver:2.4.0")
            }
        }
        val jvmSharedTest = create("jvmSharedTest") {
            dependsOn(commonTest.get())
            dependencies {
                implementation("io.ktor:ktor-client-mock:3.6.0")
                implementation("app.cash.sqldelight:sqlite-driver:2.4.0")
            }
        }
        getByName("desktopTest") {
            dependsOn(jvmSharedTest)
            // Exercise the real image presentation adapter with the same existing
            // Compose/Skiko runtime as desktopApp, without a graphical display.
            dependencies { implementation(compose.desktop.currentOs) }
        }
        getByName("androidHostTest") {
            dependsOn(jvmSharedTest)
            // Retain the previous transport only for its cancellation regressions.
            dependencies { implementation("io.ktor:ktor-client-android:3.6.0") }
        }

    }
}

sqldelight {
    databases {
        create("LocalCollectionsDatabase") {
            packageName.set("org.infinilect.app.collections.database")
            schemaOutputDirectory.set(layout.buildDirectory.dir("sqldelight/schema").get().asFile)
            // Schema-only v1 baseline and additive v2 migration are replayed by SQLDelight.
            verifyMigrations.set(true)
        }
    }
}

// Explicit metadata-only smoke check; never part of deterministic test/check/build.
tasks.register<JavaExec>("githubUpdateCheck") {
    group = "verification"
    description = "Read the official GitHub release list without downloading or installing assets."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.infinilect.app.updates.UpdateIntegrationCheck")
}

// Opt-in live check: never a dependency of test/check/build.
tasks.register<JavaExec>("gutenbergSearchCheck") {
    group = "verification"
    description = "Inspect Gutenberg's experimental OPDS2 root and one search page (no details/acquisition)."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.infinilect.app.gutenberg.GutenbergIntegrationCheck")
}

// Opt-in access diagnostic, independent of Gutenberg and of test/check/build.
tasks.register<JavaExec>("oapenLookupCheck") {
    group = "verification"
    description = "Resolve one documented OAPEN handle through public OAI-PMH; no acquisition."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.infinilect.app.oapen.OapenLookupCheck")
}

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
    "archiveOfflineDownloadCheck" to "org.infinilect.app.downloads.ArchiveOfflineDownloadCheck",
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

// Real native prototype only: not a dependency of test/check/build, no model download.
tasks.register<JavaExec>("epubNativeTranslationCheck") {
    group = "verification"
    description = "Run verified Foxlet ARM smoke inference from Kotlin (optionally under QEMU; not Android acceptance)."
    dependsOn("desktopTestClasses")
    val compilation = kotlin.targets.getByName("desktop").compilations.getByName("test")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.infinilect.app.reader.epub.EpubNativeTranslationCheck")
}
