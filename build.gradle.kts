plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
}

allprojects {
    group = "org.infinilect"
    version = "0.0.1-SNAPSHOT"
}
