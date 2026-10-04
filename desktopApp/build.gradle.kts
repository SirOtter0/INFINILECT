// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":app"))
    implementation(compose.desktop.currentOs)
}
compose.desktop {
    application {
        mainClass = "org.infinilect.app.MainKt"
        nativeDistributions {
            // The bundled runtime must support Ktor's Java engine and SQLite JDBC.
            modules("java.net.http", "java.sql")
        }
    }
}
