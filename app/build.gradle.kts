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
        }
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.infinilect.app.MainKt"
    }
}
