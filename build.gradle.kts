buildscript {
    dependencies {
        constraints {
            add("classpath", "com.google.guava:guava:33.7.2-jre") {
                because("CVE-2026-102554")
            }
            add("classpath", "org.apache.commons:commons-lang3:3.18.0") {
                because("CVE-2025-48924")
            }
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6") {
                because("CVE-2024-29371")
            }
            add("classpath", "org.jdom:jdom2:2.0.6.1") {
                because("CVE-2021-33813")
            }
            val bouncyCastleVersion = "1.85"
            for (module in listOf("bcpkix", "bcprov", "bcutil")) {
                add("classpath", "org.bouncycastle:$module-jdk18on:$bouncyCastleVersion") {
                    because("Keep Bouncy Castle aligned and address CVE-2026-8763 and CVE-2026-13506")
                }
            }
        }
    }
}

plugins {
    kotlin("multiplatform") version "2.4.20" apply false
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.compose") version "1.12.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("com.android.application") version "9.3.1" apply false
    id("app.cash.sqldelight") version "2.4.0" apply false
}

allprojects {
    group = "org.infinilect"
    version = "0.0.1-SNAPSHOT"
}
