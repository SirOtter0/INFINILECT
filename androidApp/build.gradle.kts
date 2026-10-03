// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
plugins {
    id("com.android.application")
    kotlin("plugin.compose")
}
android {
    namespace = "org.infinilect.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.infinilect.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-SNAPSHOT"
    }
    buildFeatures { compose = true }
    // Debug uses the upstream prebuilt path library unchanged; no native build/NDK.
    packaging { jniLibs.keepDebugSymbols.add("**/libandroidx.graphics.path.so") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
dependencies {
    implementation(project(":app"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    // The manifest directly references this provider to disable automatic font
    // initialization; use the same official stable version at compile/runtime.
    implementation("androidx.startup:startup-runtime:1.2.0")
}
