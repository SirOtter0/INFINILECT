plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight")
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm("desktop")
    android {
        namespace = "org.infinilect.shared"
        compileSdk = 37
        minSdk = 26
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
                implementation("app.cash.sqldelight:sqlite-driver:2.4.0")
            }
        }
        getByName("androidMain") {
            dependsOn(jvmSharedMain)
            dependencies {
                implementation("io.ktor:ktor-client-android:3.6.0")
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
        getByName("desktopTest") { dependsOn(jvmSharedTest) }
        getByName("androidHostTest") {
            dependsOn(jvmSharedTest)
        }

    }
}

sqldelight {
    databases {
        create("LocalCollectionsDatabase") {
            packageName.set("org.infinilect.app.collections.database")
            schemaOutputDirectory.set(layout.buildDirectory.dir("sqldelight/schema").get().asFile)
            // Initial schema only: no historical migration/database baseline yet.
            // SQL definition verification stays enabled. The first schema change
            // must introduce a historical baseline and enable migration replay.
            verifyMigrations.set(false)
        }
    }
}

// Opt-in live check: never a dependency of test/check/build.
tasks.register<JavaExec>("gutenbergSearchCheck") {
    group = "verification"
    description = "Inspect Gutenberg's experimental OPDS2 root, one search page and one metadata record (no acquisition)."
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
