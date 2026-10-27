// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.infinilect.app.network.*
import java.io.*
import java.net.URI
import java.nio.file.*
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest

internal interface UpdateInstaller {
    val installed: InstalledUpdateBuild
    suspend fun inspect(file: Path): ApkIdentity
    suspend fun handoff(file: Path): InstallHandoff
    suspend fun automaticAllowed(): Boolean
    suspend fun installationCancelled(): Boolean = false
    suspend fun installationFailure(): UpdateFailure? = null
    fun requestPermission()
    fun openOfficialPage()
}

/** A small dedicated client using the existing platform engine. No cookies, auth, payload
 * cache or automatic redirects. API discovery never downloads an APK or book. */
internal class GitHubUpdates(
    private val directory: Path?,
    private val installer: UpdateInstaller,
    private val trust: UpdateTrust = productionUpdateTrust(),
    private val engine: HttpClientEngine = platformHttpEngine(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val preferencesDirectory: Path? = directory,
) : UpdateBackend {
    override val installed get()=installer.installed
    override val trustedInstallationConfigured get()=installed.android && !installed.development && trust.configured
    private val client=HttpClient(engine) {
        followRedirects=false;expectSuccess=false
        install(HttpTimeout) {connectTimeoutMillis=5_000;socketTimeoutMillis=15_000;requestTimeoutMillis=120_000}
    }
    private val gate=Mutex()
    private val preferenceGate=Mutex()
    private var prepared: Pair<PreparedUpdate,SignedUpdate>?=null
    private var leaseChannel: java.nio.channels.FileChannel?=null
    private var lease: java.nio.channels.FileLock?=null
    private fun root(directory: Path? = this.directory): Path {
        val p=directory?.takeIf {it.isAbsolute} ?: updateError(UpdateFailure.STORAGE)
        try {Files.createDirectories(p)} catch(_: IOException) {updateError(UpdateFailure.STORAGE)} catch(_: SecurityException) {updateError(UpdateFailure.STORAGE)}
        if(!Files.isDirectory(p,NOFOLLOW_LINKS) || Files.isSymbolicLink(p)) updateError(UpdateFailure.STORAGE)
        try {
            if(Files.getFileAttributeView(p,java.nio.file.attribute.PosixFileAttributeView::class.java,NOFOLLOW_LINKS)!=null)
                Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        } catch(_: IOException) {updateError(UpdateFailure.STORAGE)} catch(_: SecurityException) {updateError(UpdateFailure.STORAGE)}
        return p
    }
    private fun acquireLease() {
        if(lease!=null) return
        val channel=try {java.nio.channels.FileChannel.open(root(preferencesDirectory).resolve("transfer.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,NOFOLLOW_LINKS)}
        catch(_: IOException) {updateError(UpdateFailure.STORAGE)} catch(_: SecurityException) {updateError(UpdateFailure.STORAGE)}
        try {
            val lock=try {channel.tryLock()} catch(_: java.nio.channels.OverlappingFileLockException) {null}
            if(lock==null) updateError(UpdateFailure.BUSY)
            leaseChannel=channel;lease=lock
        } catch(e: Exception) {channel.close();throw e}
    }
    private fun releaseLease() {
        val lock=lease;val channel=leaseChannel;lease=null;leaseChannel=null
        var failed=false
        try {lock?.release()} catch(_: IOException) {failed=true}
        try {channel?.close()} catch(_: IOException) {failed=true}
        if(failed) updateError(UpdateFailure.STORAGE)
    }
    private fun clearFiles(release: Boolean = true) {
        prepared=null
        if(directory==null) return
        val p=root();acquireLease()
        try {listOf("payload.part","candidate.apk").forEach {Files.deleteIfExists(p.resolve(it))}}
        catch(_: IOException) {releaseLease();updateError(UpdateFailure.STORAGE)}
        catch(_: SecurityException) {releaseLease();updateError(UpdateFailure.STORAGE)}
        finally {if(release) releaseLease()}
    }
    override suspend fun discard() = withContext(dispatcher) {gate.withLock {clearFiles()}}
    override suspend fun loadPreferences(): UpdatePreferences = withContext(dispatcher) {
        gate.withLock {
            clearFiles() // Never reuse an APK after process death, even a complete one.
            val file=root(preferencesDirectory).resolve("update.preferences")
            if(!Files.exists(file,NOFOLLOW_LINKS)) return@withLock UpdatePreferences()
            try {
                if(!Files.isRegularFile(file,NOFOLLOW_LINKS) || Files.size(file) !in 32..1024) updateError(UpdateFailure.PREFERENCES)
                val bytes=Files.newInputStream(file,NOFOLLOW_LINKS).use {input ->
                    val buffer=ByteArray(1025);var count=0
                    while(count<buffer.size) {ensureActive();val n=input.read(buffer,count,buffer.size-count);if(n<0) break;count+=n}
                    if(count>1024) updateError(UpdateFailure.PREFERENCES)
                    buffer.copyOf(count)
                }
                val payload=bytes.dropLast(32).toByteArray()
                if(!MessageDigest.isEqual(bytes.takeLast(32).toByteArray(),MessageDigest.getInstance("SHA-256").digest(payload))) updateError(UpdateFailure.PREFERENCES)
                DataInputStream(ByteArrayInputStream(payload)).use {
                    if(it.readInt()!=0x494e5531) updateError(UpdateFailure.PREFERENCES)
                    val p=UpdatePreferences(UpdateChannel.valueOf(it.readUTF()),it.readBoolean(),it.readLong(),it.readLong(),UpdateStage.valueOf(it.readUTF()),it.readUTF().ifEmpty {null})
                    if(it.available()!=0 || p.lastAttempt<0 || p.lastSuccess<0 || (p.dismissedTag!=null && ReleaseVersion.parse(p.dismissedTag)==null)) updateError(UpdateFailure.PREFERENCES)
                    p
                }
            } catch(e: UpdateException) {throw e} catch(_: Exception) {updateError(UpdateFailure.PREFERENCES)}
        }
    }
    override suspend fun savePreferences(value: UpdatePreferences) = withContext(dispatcher) {
        preferenceGate.withLock {
            val p=root(preferencesDirectory);val temp=p.resolve("preferences.part")
            val payload=ByteArrayOutputStream().also {out -> DataOutputStream(out).use {
                it.writeInt(0x494e5531);it.writeUTF(value.channel.name);it.writeBoolean(value.automatic);it.writeLong(value.lastAttempt);it.writeLong(value.lastSuccess);it.writeUTF(value.lastResult.name);it.writeUTF(value.dismissedTag.orEmpty())
            }}.toByteArray()
            try {
                Files.deleteIfExists(temp)
                java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW_LINKS).use {out ->
                    val bytes=java.nio.ByteBuffer.wrap(payload+MessageDigest.getInstance("SHA-256").digest(payload))
                    while(bytes.hasRemaining()) {ensureActive();out.write(bytes)};out.force(true)
                }
                Files.move(temp,p.resolve("update.preferences"),ATOMIC_MOVE,REPLACE_EXISTING)
            } catch(e: CancellationException) {throw e} catch(_: IOException) {updateError(UpdateFailure.PREFERENCES)}
            finally {Files.deleteIfExists(temp)}
            Unit
        }
    }
    override suspend fun latest(channel: UpdateChannel): OfficialRelease? = withContext(dispatcher) {
        val current=ReleaseVersion.parse(installed.versionName,true) ?: updateError(UpdateFailure.INCOMPATIBLE)
        val releases=ArrayList<OfficialRelease>()
        for(page in 1..3) {
            val bytes=readSmall("https://api.github.com/repos/SirOtter0/INFINILECT/releases?per_page=30&page=$page",1_048_576,false)
            val list=updateJson(bytes,1_048_576) as? JsonArray ?: updateError(UpdateFailure.FORMAT)
            if(list.size>30) updateError(UpdateFailure.FORMAT)
            for(item in list) {
                val o=item as? JsonObject ?: updateError(UpdateFailure.FORMAT)
                if(o.flag("draft")) continue
                val tag=o.text("tag_name");val v=ReleaseVersion.parse(tag) ?: continue
                val pre=o.flag("prerelease")
                if(pre!=(v.beta!=null) || (channel==UpdateChannel.STABLE && pre) || v<=current) continue
                val raw=o["assets"] as? JsonArray ?: updateError(UpdateFailure.FORMAT)
                if(raw.size>30) updateError(UpdateFailure.FORMAT)
                val assets=raw.map {a ->
                    val p=a as? JsonObject ?: updateError(UpdateFailure.FORMAT)
                    val name=p.text("name");val url=p.text("browser_download_url");val size=p.number("size")
                    if(!officialAsset(url,tag,name) || size !in 1..MAX_UPDATE_BYTES) updateError(UpdateFailure.FORMAT)
                    ReleaseAsset(name,url,size)
                }
                if(assets.map {it.name}.distinct().size!=assets.size) updateError(UpdateFailure.FORMAT)
                releases+=OfficialRelease(tag,v,pre,safeNotes((o["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()),assets)
            }
            if(list.size<30) break
        }
        releases.maxWithOrNull(compareBy<OfficialRelease> {it.version}.thenBy {it.tag})
    }
    private fun redirected(previous: String, location: String): String {
        val next=try {URI(previous).resolve(location)} catch(_: Exception) {updateError(UpdateFailure.REDIRECT)}
        if(next.scheme!="https" || next.rawUserInfo!=null || next.port!=-1 || next.rawFragment!=null || next.toASCIIString().length>8192 ||
            next.host !in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com") || !next.rawPath.startsWith("/github-production-release-asset/")) updateError(UpdateFailure.REDIRECT)
        return next.toASCIIString()
    }
    private suspend fun receive(url: String, limit: Long, asset: Boolean, sink: suspend (ByteArray,Int)->Unit) {
        var next=url
        try {
            for(hop in 0..3) {
                var redirect: String?=null
                client.prepareGet(next) {
                    header(HttpHeaders.UserAgent,PROJECT_USER_AGENT)
                    header(HttpHeaders.Accept,if(asset) "application/octet-stream" else "application/vnd.github+json")
                    if(!asset) header("X-GitHub-Api-Version","2022-11-28")
                }.execute {response ->
                    val status=response.status.value
                    if(status in setOf(301,302,303,307,308)) {
                        if(!asset || hop==3) updateError(UpdateFailure.REDIRECT)
                        redirect=redirected(next,response.headers[HttpHeaders.Location] ?: updateError(UpdateFailure.REDIRECT))
                        return@execute
                    }
                    if(status==403 || status==429) updateError(UpdateFailure.RATE_LIMIT)
                    if(status!=200) updateError(UpdateFailure.HTTP)
                    val type=response.contentType()?.withoutParameters()?.toString()
                    if(if(asset) type !in setOf("application/octet-stream","application/vnd.android.package-archive","application/zip","application/json") else type !in setOf("application/json","application/vnd.github+json")) updateError(UpdateFailure.FORMAT)
                    val lengthHeader=response.headers[HttpHeaders.ContentLength]
                    val contentLength=lengthHeader?.toLongOrNull()
                    if(lengthHeader!=null && contentLength==null) updateError(UpdateFailure.FORMAT)
                    if(contentLength!=null && (contentLength<0 || contentLength>limit)) updateError(UpdateFailure.INTEGRITY)
                    val channel=response.bodyAsChannel();val buffer=ByteArray(32_768);var total=0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=channel.readAvailable(buffer,0,buffer.size);if(count<0) break;if(count==0) {yield();continue}
                        total+=count;if(total>limit) updateError(UpdateFailure.INTEGRITY);sink(buffer,count)
                    }
                    if(contentLength!=null && total!=contentLength) updateError(UpdateFailure.INTEGRITY)
                }
                val target=redirect ?: return
                next=target
            }
        } catch(e: CancellationException) {throw e}
        catch(e: UpdateException) {throw e}
        catch(_: HttpRequestTimeoutException) {updateError(UpdateFailure.TIMEOUT)}
        catch(_: java.net.SocketTimeoutException) {updateError(UpdateFailure.TIMEOUT)}
        catch(_: IOException) {updateError(UpdateFailure.CONNECTION)}
    }
    private suspend fun readSmall(url: String, limit: Int, asset: Boolean): ByteArray {
        val out=ByteArrayOutputStream()
        receive(url,limit.toLong(),asset) {buffer,count -> out.write(buffer,0,count)}
        return out.toByteArray()
    }
    override suspend fun prepare(release: OfficialRelease, progress: (Long,Long,Boolean)->Unit): PreparedUpdate = withContext(dispatcher) {
        gate.withLock {
            if(!trustedInstallationConfigured) updateError(UpdateFailure.TRUST_NOT_CONFIGURED)
            clearFiles(release=false)
            val p=root();val part=p.resolve("payload.part");var success=false
            try {
                val asset=release.assets.singleOrNull {it.name=="infinilect-update-v1.json"} ?: updateError(UpdateFailure.FORMAT)
                if(asset.size>32_768 || !officialAsset(asset.url,release.tag,asset.name)) updateError(UpdateFailure.FORMAT)
                val envelope=readSmall(asset.url,32_768,true)
                if(envelope.size.toLong()!=asset.size) updateError(UpdateFailure.INTEGRITY)
                val manifest=verifyUpdateManifest(envelope,trust,release,installed,if(release.prerelease) UpdateChannel.BETA else UpdateChannel.STABLE,clock())
                val apk=release.assets.single {it.name==manifest.asset}
                if(Files.getFileStore(p).usableSpace<manifest.bytes+4*1_048_576) updateError(UpdateFailure.STORAGE)
                val digest=MessageDigest.getInstance("SHA-256");var count=0L;var last=0L
                Files.newOutputStream(part,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW_LINKS).use {out ->
                    receive(apk.url,manifest.bytes,true) {buffer,n ->
                        out.write(buffer,0,n);digest.update(buffer,0,n);count+=n
                        val now=clock();if(now-last>=100 || count==manifest.bytes) {progress(count,manifest.bytes,false);last=now}
                    }
                }
                if(count!=manifest.bytes || digest.digest().joinToString("") {"%02x".format(it)}!=manifest.sha256) updateError(UpdateFailure.INTEGRITY)
                progress(count,manifest.bytes,true)
                validateApk(installer.inspect(part),manifest,installed)
                currentCoroutineContext().ensureActive()
                Files.move(part,p.resolve("candidate.apk"),ATOMIC_MOVE,REPLACE_EXISTING)
                val token=PreparedUpdate();prepared=token to manifest;success=true;token
            } catch(e: CancellationException) {throw e} catch(e: UpdateException) {throw e} catch(_: IOException) {updateError(UpdateFailure.STORAGE)}
            finally {if(!success) clearFiles()}
        }
    }
    override suspend fun install(value: PreparedUpdate): InstallHandoff = withContext(dispatcher) {
        gate.withLock {
            if(!trustedInstallationConfigured) updateError(UpdateFailure.TRUST_NOT_CONFIGURED)
            val manifest=prepared?.takeIf {it.first==value}?.second ?: updateError(UpdateFailure.INTEGRITY)
            if(clock()>=manifest.expires) {clearFiles();updateError(UpdateFailure.SIGNATURE)}
            val file=root().resolve("candidate.apk")
            try {
                // Recheck the entire file and installed identity immediately before system handoff.
                val digest=MessageDigest.getInstance("SHA-256")
                if(!Files.isRegularFile(file,NOFOLLOW_LINKS) || Files.size(file)!=manifest.bytes) updateError(UpdateFailure.INTEGRITY)
                Files.newInputStream(file,NOFOLLOW_LINKS).use {input -> val b=ByteArray(32_768);while(true) {ensureActive();val n=input.read(b);if(n<0) break;digest.update(b,0,n)}}
                if(digest.digest().joinToString("") {"%02x".format(it)}!=manifest.sha256) updateError(UpdateFailure.INTEGRITY)
                validateApk(installer.inspect(file),manifest,installed)
                val result=installer.handoff(file)
                if(result==InstallHandoff.DELEGATED) clearFiles() // Android owns its copied session now.
                result
            } catch(e: CancellationException) {throw e} catch(e: UpdateException) {clearFiles();throw e} catch(_: Exception) {clearFiles();updateError(UpdateFailure.INSTALLER)}
        }
    }
    override suspend fun automaticAllowed()=installer.automaticAllowed()
    override suspend fun installationCancelled()=installer.installationCancelled()
    override suspend fun installationFailure()=installer.installationFailure()
    override fun requestInstallPermission()=installer.requestPermission()
    override fun openOfficialPage()=installer.openOfficialPage()
    override fun close() {try {releaseLease()} finally {client.close();engine.close()}}
}

internal actual fun formatUpdateTime(epoch: Long, locale: String): String = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT,java.util.Locale.forLanguageTag(locale)).format(java.util.Date(epoch))
