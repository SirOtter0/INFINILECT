plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    applyDefaultHierarchyTemplate()
    jvm()
    android {
        namespace = "org.infinilect.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        withHostTest {}
    }
    jvmToolchain(21)
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
