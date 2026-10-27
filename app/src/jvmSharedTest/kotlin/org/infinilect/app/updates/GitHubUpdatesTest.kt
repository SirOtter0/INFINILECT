// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.nio.file.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubUpdatesTest {
    private class Installer : UpdateInstaller {
        override var installed=SyntheticUpdates.installed
        var inspected=0;var delegated=0;var mismatch=false;var permission=false
        override suspend fun inspect(file: Path): ApkIdentity {assertContentEquals(SyntheticUpdates.bytes,Files.readAllBytes(file));inspected++;return SyntheticUpdates.identity().let {if(mismatch) it.copy(certificates=setOf("2".repeat(64))) else it}}
        override suspend fun handoff(file: Path): InstallHandoff {assertTrue(Files.isRegularFile(file));if(permission) return InstallHandoff.PERMISSION;delegated++;return InstallHandoff.DELEGATED}
        override suspend fun automaticAllowed()=true
        override fun requestPermission()=Unit
        override fun openOfficialPage()=Unit
    }
    private fun jsonRelease(tag: String, pre: Boolean = false, draft: Boolean = false)=buildJsonObject {
        put("tag_name",tag);put("prerelease",pre);put("draft",draft);put("body","<script>plain notes</script>");putJsonArray("assets") {}
    }
    @Test fun discoverySeparatesChannelsUsesNumericVersionsAndNeverDownloadsPayloads()=runTest {
        val calls=mutableListOf<String>();val root=Files.createTempDirectory("updates-test")
        val engine=MockEngine {r ->
            calls+=r.url.toString();assertEquals("api.github.com",r.url.host);assertEquals("application/vnd.github+json",r.headers[HttpHeaders.Accept])
            respond(JsonArray(listOf(jsonRelease("v1.9.0"),jsonRelease("v1.10.0"),jsonRelease("v1.11.0-beta.2",true),jsonRelease("v2.0.0-rc.1",true),jsonRelease("v3.0.0",draft=true),jsonRelease("v4.0.0",true),jsonRelease("v0.9.0"))).toString(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        val b=GitHubUpdates(root,Installer(),engine=engine,dispatcher=StandardTestDispatcher(testScheduler))
        assertEquals("v1.10.0",b.latest(UpdateChannel.STABLE)?.tag)
        assertEquals("v1.11.0-beta.2",b.latest(UpdateChannel.BETA)?.tag)
        assertEquals(2,calls.size);assertTrue(calls.all {it.contains("per_page=30&page=1")})
        b.close()
    }
    @Test fun boundedPaginationFindsLaterCompatibleRelease()=runTest {
        var calls=0
        val b=GitHubUpdates(Files.createTempDirectory("updates-test"),Installer(),engine=MockEngine {
            calls++;respond(JsonArray(if(calls==1) List(30) {jsonRelease("v1.0.0")} else listOf(jsonRelease("v1.2.0"))).toString(),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        },dispatcher=StandardTestDispatcher(testScheduler))
        assertEquals("v1.2.0",b.latest(UpdateChannel.STABLE)?.tag);assertEquals(2,calls);b.close()
    }
    @Test fun httpRateTimeoutConnectionAndInvalidMetadataAreClassified()=runTest {
        suspend fun failure(expected: UpdateFailure, handler: MockRequestHandler) {
            val b=GitHubUpdates(Files.createTempDirectory("updates-test"),Installer(),engine=MockEngine(handler),dispatcher=StandardTestDispatcher(testScheduler))
            try {assertEquals(expected,assertFailsWith<UpdateException> {b.latest(UpdateChannel.STABLE)}.failure)} finally {b.close()}
        }
        for(code in listOf(403,429)) failure(UpdateFailure.RATE_LIMIT) {respond("",HttpStatusCode.fromValue(code))}
        failure(UpdateFailure.HTTP) {respond("",HttpStatusCode.InternalServerError)}
        failure(UpdateFailure.FORMAT) {respond("{}",headers=headersOf(HttpHeaders.ContentType,"application/json"))}
        failure(UpdateFailure.FORMAT) {respond("[]",headers=headersOf(HttpHeaders.ContentType,"text/html"))}
        failure(UpdateFailure.FORMAT) {respond("[broken",headers=headersOf(HttpHeaders.ContentType,"application/json"))}
        failure(UpdateFailure.REDIRECT) {respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://evil.invalid/"))}
        failure(UpdateFailure.TIMEOUT) {throw java.net.SocketTimeoutException("Synthetic only")}
        failure(UpdateFailure.CONNECTION) {throw IOException("Synthetic offline")}
        failure(UpdateFailure.CONNECTION) {throw javax.net.ssl.SSLHandshakeException("Synthetic TLS failure")}
    }
    @Test fun signedSyntheticPayloadIsFullyVerifiedAndRecheckedBeforeSystemCopy()=runTest {
        val envelope=SyntheticUpdates.envelope();val root=Files.createTempDirectory("updates-test");val installer=Installer();var requests=0
        val b=GitHubUpdates(root,installer,SyntheticUpdates.trust,MockEngine {r ->
            requests++;respond(if(r.url.encodedPath.endsWith(".json")) envelope else SyntheticUpdates.bytes,headers=headersOf(HttpHeaders.ContentType,"application/octet-stream"))
        },{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
        val progress=mutableListOf<Triple<Long,Long,Boolean>>()
        val token=b.prepare(SyntheticUpdates.release(envelope)) {a,c,v -> progress+=Triple(a,c,v)}
        assertEquals(2,requests);assertEquals(1,installer.inspected);assertTrue(progress.last().third)
        assertFalse(Files.exists(root.resolve("payload.part")));assertTrue(Files.exists(root.resolve("candidate.apk")))
        installer.permission=true;assertEquals(InstallHandoff.PERMISSION,b.install(token));assertTrue(Files.exists(root.resolve("candidate.apk")))
        installer.permission=false;assertEquals(InstallHandoff.DELEGATED,b.install(token));assertEquals(1,installer.delegated);assertFalse(Files.exists(root.resolve("candidate.apk")));assertEquals(3,installer.inspected)
        assertEquals(UpdateFailure.INTEGRITY,assertFailsWith<UpdateException> {b.install(token)}.failure)
        b.close()
    }
    @Test fun hashCertificateOversizeFailuresAndPostVerificationTamperingRemoveTemporaryFiles()=runTest {
        val envelope=SyntheticUpdates.envelope()
        for(mode in listOf("hash","certificate","oversize","tamper")) {
            val root=Files.createTempDirectory("updates-test");val installer=Installer();installer.mismatch=mode=="certificate"
            val b=GitHubUpdates(root,installer,SyntheticUpdates.trust,MockEngine {r ->
                val bytes=if(r.url.encodedPath.endsWith(".json")) envelope else if(mode=="hash") SyntheticUpdates.bytes.copyOf().apply {this[0]=0} else SyntheticUpdates.bytes
                val headers=if(mode=="oversize" && !r.url.encodedPath.endsWith(".json")) headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"),HttpHeaders.ContentLength to listOf((MAX_UPDATE_BYTES+1).toString())) else headersOf(HttpHeaders.ContentType,"application/octet-stream")
                respond(bytes,headers=headers)
            },{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
            if(mode=="tamper") {
                val token=b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->};Files.write(root.resolve("candidate.apk"),ByteArray(SyntheticUpdates.bytes.size))
                assertEquals(UpdateFailure.INTEGRITY,assertFailsWith<UpdateException> {b.install(token)}.failure);assertEquals(0,installer.delegated)
            } else assertEquals(if(mode=="certificate") UpdateFailure.SIGNATURE else UpdateFailure.INTEGRITY,assertFailsWith<UpdateException> {b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->}}.failure)
            assertFalse(Files.exists(root.resolve("payload.part")));assertFalse(Files.exists(root.resolve("candidate.apk")));b.close()
        }
    }
    @Test fun redirectsAreRestrictedAndDoNotWeakenHttpsOrRepositoryBinding()=runTest {
        val envelope=SyntheticUpdates.envelope()
        for(target in listOf("http://release-assets.githubusercontent.com/github-production-release-asset/1/a","https://evil.invalid/a","https://user@release-assets.githubusercontent.com/github-production-release-asset/1/a","https://github.com/fork/INFINILECT/releases/download/v1.1.0/a","https://release-assets.githubusercontent.com/other/a","https://127.0.0.1/a")) {
            var calls=0;val b=GitHubUpdates(Files.createTempDirectory("updates-test"),Installer(),SyntheticUpdates.trust,MockEngine {calls++;respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,target))},{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
            assertEquals(UpdateFailure.REDIRECT,assertFailsWith<UpdateException> {b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->}}.failure);assertEquals(1,calls);b.close()
        }
        var calls=0
        val b=GitHubUpdates(Files.createTempDirectory("updates-test"),Installer(),SyntheticUpdates.trust,MockEngine {r ->
            calls++
            if(r.url.host=="github.com") respond("",HttpStatusCode.Found,headersOf(HttpHeaders.Location,"https://release-assets.githubusercontent.com/github-production-release-asset/1/${if(r.url.encodedPath.endsWith(".json")) "manifest" else "apk"}?signature=synthetic"))
            else respond(if(r.url.encodedPath.endsWith("manifest")) envelope else SyntheticUpdates.bytes,headers=headersOf(HttpHeaders.ContentType,"application/octet-stream"))
        },{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
        b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->};assertEquals(4,calls);b.discard();b.close()
    }
    @Test fun interruptedDownloadsCancelCleanlyWithoutInspectingPartialContent()=runTest {
        val envelope=SyntheticUpdates.envelope();val root=Files.createTempDirectory("updates-test");val installer=Installer();val started=CompletableDeferred<Unit>();val body=ByteChannel(true)
        val b=GitHubUpdates(root,installer,SyntheticUpdates.trust,MockEngine {r ->
            if(r.url.encodedPath.endsWith(".json")) respond(envelope,headers=headersOf(HttpHeaders.ContentType,"application/octet-stream"))
            else {started.complete(Unit);respond(body,headers=headersOf(HttpHeaders.ContentType,"application/octet-stream"))}
        },{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
        val job=launch {b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->}}
        started.await();body.writeFully(byteArrayOf(1,2,3));runCurrent();job.cancelAndJoin()
        assertEquals(0,installer.inspected);assertFalse(Files.exists(root.resolve("payload.part")));assertFalse(Files.exists(root.resolve("candidate.apk")));body.cancel();b.close()
    }
    @Test fun preferencesAreAtomicIndependentOfApkCacheAndRecoverCorruptRecords()=runTest {
        val root=Files.createTempDirectory("updates-test");val prefs=Files.createTempDirectory("updates-prefs")
        fun backend()=GitHubUpdates(root,Installer(),engine=MockEngine {error("No network expected")},dispatcher=StandardTestDispatcher(testScheduler),preferencesDirectory=prefs)
        val b=backend();assertEquals(UpdatePreferences(),b.loadPreferences())
        val expected=UpdatePreferences(UpdateChannel.BETA,true,1000,900,UpdateStage.AVAILABLE,"v1.1.0")
        b.savePreferences(expected);Files.write(root.resolve("payload.part"),byteArrayOf(1));Files.write(root.resolve("candidate.apk"),byteArrayOf(1));b.close()
        val reopened=backend();assertEquals(expected,reopened.loadPreferences());assertFalse(Files.exists(root.resolve("payload.part")));assertFalse(Files.exists(root.resolve("candidate.apk")))
        Files.write(prefs.resolve("update.preferences"),byteArrayOf(1))
        assertEquals(UpdateFailure.PREFERENCES,assertFailsWith<UpdateException> {reopened.loadPreferences()}.failure)
        reopened.savePreferences(UpdatePreferences());assertEquals(UpdatePreferences(),reopened.loadPreferences());assertFalse(Files.exists(prefs.resolve("preferences.part")));reopened.close()
    }
    @Test fun transportFailureCanRetryAndCleanupNeverTouchesUnrelatedFiles()=runTest {
        val envelope=SyntheticUpdates.envelope();val root=Files.createTempDirectory("updates-test");val sentinel=root.resolve("unrelated-record");Files.write(sentinel,byteArrayOf(4,5,6));var attempt=0
        val b=GitHubUpdates(root,Installer(),SyntheticUpdates.trust,MockEngine {r ->
            if(r.url.encodedPath.endsWith(".apk") && attempt++==0) throw IOException("Synthetic interrupted transport")
            respond(if(r.url.encodedPath.endsWith(".json")) envelope else SyntheticUpdates.bytes,headers=headersOf(HttpHeaders.ContentType,"application/octet-stream"))
        },{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
        assertEquals(UpdateFailure.CONNECTION,assertFailsWith<UpdateException> {b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->}}.failure)
        assertFalse(Files.exists(root.resolve("payload.part")));b.prepare(SyntheticUpdates.release(envelope)) {_,_,_->};b.discard()
        assertContentEquals(byteArrayOf(4,5,6),Files.readAllBytes(sentinel));b.close()
        val file=Files.createTempFile("unavailable-update-root","record")
        val broken=GitHubUpdates(file,Installer(),engine=MockEngine {error("No network")},dispatcher=StandardTestDispatcher(testScheduler))
        assertEquals(UpdateFailure.STORAGE,assertFailsWith<UpdateException> {broken.loadPreferences()}.failure);assertTrue(Files.isRegularFile(file));broken.close()
    }
    @Test fun aSecondOwnerCannotEvictOrReplaceTheVerifiedCandidate()=runTest {
        val envelope=SyntheticUpdates.envelope();val root=Files.createTempDirectory("updates-concurrent")
        fun backend()=GitHubUpdates(root,Installer(),SyntheticUpdates.trust,MockEngine {r -> respond(if(r.url.encodedPath.endsWith(".json")) envelope else SyntheticUpdates.bytes,headers=headersOf(HttpHeaders.ContentType,"application/octet-stream"))},{SyntheticUpdates.now},StandardTestDispatcher(testScheduler))
        val first=backend();val second=backend()
        val token=first.prepare(SyntheticUpdates.release(envelope)) {_,_,_->}
        assertEquals(UpdateFailure.BUSY,assertFailsWith<UpdateException> {second.prepare(SyntheticUpdates.release(envelope)) {_,_,_->}}.failure)
        assertEquals(UpdateFailure.BUSY,assertFailsWith<UpdateException> {second.discard()}.failure)
        assertContentEquals(SyntheticUpdates.bytes,Files.readAllBytes(root.resolve("candidate.apk")))
        assertEquals(InstallHandoff.DELEGATED,first.install(token))
        second.prepare(SyntheticUpdates.release(envelope)) {_,_,_->};second.discard();first.close();second.close()
    }
}
