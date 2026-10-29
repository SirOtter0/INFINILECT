// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import com.android.build.api.artifact.SingleArtifact
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

/** All current variants are metadata-only updaters. No remote key/release or Gradle
 * property may silently turn one into a distribution installer. Guard the merged
 * artifact so transitive manifests are covered too, rather than grepping source XML. */
abstract class VerifyUpdaterManifest : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty
    @get:Input abstract val variantName: Property<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        manifestFile.get().asFile.inputStream().use { validate(it) }
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText("PASS: ${variantName.get()}; no installation permission; receiver non-exported\n") }
    }
    companion object {
        fun validate(input: java.io.InputStream) {
            val factory=DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware=true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
            }
            val manifest=factory.newDocumentBuilder().parse(input).documentElement
            val ns="http://schemas.android.com/apk/res/android"
            check(manifest.tagName=="manifest" && manifest.getAttribute("package")=="org.infinilect.app") { "Unexpected application identity" }
            val nodes=manifest.getElementsByTagName("*")
            val elements=(0 until nodes.length).map {nodes.item(it) as org.w3c.dom.Element}
            val permissions=elements.filter {it.tagName.startsWith("uses-permission")}.map {it.getAttributeNS(ns,"name")}.toSet()
            check(permissions==setOf("android.permission.INTERNET","android.permission.ACCESS_NETWORK_STATE","org.infinilect.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")) { "Unexpected permissions in metadata-only variant" }
            val receivers=elements.filter {it.tagName=="receiver" && it.getAttributeNS(ns,"name")=="org.infinilect.app.updates.UpdateInstallReceiver"}
            check(receivers.size==1 && receivers.single().getAttributeNS(ns,"exported")=="false") { "Update receiver must be explicitly non-exported" }
            check(receivers.single().getElementsByTagName("intent-filter").length==0) { "Update callback must remain explicit" }
        }
    }
}

val testUpdaterManifestPolicy=tasks.register("testUpdaterManifestPolicy") {
    group="verification"
    description="Exercise updater manifest policy with safe and hostile synthetic manifests."
    doLast {
        val good="""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.infinilect.app">
            <uses-permission android:name="android.permission.INTERNET"/>
            <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
            <uses-permission android:name="org.infinilect.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"/>
            <application><receiver android:name="org.infinilect.app.updates.UpdateInstallReceiver" android:exported="false"/></application></manifest>"""
        fun verify(xml: String)=VerifyUpdaterManifest.validate(xml.byteInputStream())
        verify(good)
        val bad=listOf(
            good.replace("<application>","<uses-permission android:name=\"android.permission.REQUEST_INSTALL_PACKAGES\"/><application>"),
            good.replace("<application>","<uses-permission-sdk-23 android:name=\"android.permission.REQUEST_INSTALL_PACKAGES\"/><application>"),
            good.replace("<application>","<uses-permission android:name=\"android.permission.INSTALL_PACKAGES\"/><application>"),
            good.replace("android:exported=\"false\"","android:exported=\"true\""),
            good.replace(" android:exported=\"false\"",""),
            good.replace("org.infinilect.app.updates.UpdateInstallReceiver","other.Receiver"),
            good.replace("package=\"org.infinilect.app\"","package=\"other.app\"")
        )
        bad.forEach {xml -> check(runCatching {verify(xml)}.isFailure) {"Unsafe synthetic manifest was accepted"} }
        logger.lifecycle("Updater manifest policy: 8 synthetic cases passed")
    }
}

androidComponents.onVariants { variant ->
    val suffix=variant.name.replaceFirstChar {it.uppercaseChar()}
    val verification=tasks.register<VerifyUpdaterManifest>("verify${suffix}UpdaterManifest") {
        group="verification"
        description="Verify installation permissions and receiver in the final ${variant.name} merged manifest."
        manifestFile.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        variantName.set(variant.name)
        reportFile.set(layout.buildDirectory.file("reports/updater-manifests/${variant.name}.txt"))
    }
    tasks.matching {it.name=="assemble$suffix" || it.name=="check"}.configureEach {dependsOn(verification,testUpdaterManifestPolicy)}
}

// Checks the installed-artifact boundary, not just the JVM test classpath.
tasks.register("verifyDebugLanguageResources") {
    group = "verification"
    description = "Verify all shipped interface languages in the assembled debug APK."
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/androidApp-debug.apk")
    val preparedResources = project(":app").layout.buildDirectory.dir("generated/compose/resourceGenerator/preparedResources/commonMain/composeResources")
    inputs.file(apk)
    inputs.dir(preparedResources)
    doLast {
        ZipFile(apk.get().asFile).use { archive ->
            listOf("values", "values-es", "values-ca").forEach { locale ->
                listOf("strings", "plurals").forEach { resource ->
                    val path = "assets/composeResources/org.infinilect.app.generated.resources/$locale/$resource.commonMain.cvr"
                    val entry = checkNotNull(archive.getEntry(path)) { "Missing packaged language resource: $path" }
                    val prepared = preparedResources.get().file("$locale/$resource.commonMain.cvr").asFile
                    check(archive.getInputStream(entry).use { it.readBytes() }.contentEquals(prepared.readBytes())) { "Packaged language resource differs from generated resource: $locale/$resource" }
                }
            }
        }
    }
}
