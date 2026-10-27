// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val MAX_UPDATE_BYTES = 256L * 1024 * 1024
internal const val UPDATE_PACKAGE = "org.infinilect.app"
private val jsonNumber=Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
internal data class UpdateTrust(val manifestKeys: Map<String,ByteArray>, val apkCertificates: Set<String>, val revokedKeys: Set<String> = emptySet()) {
    val configured get()=manifestKeys.isNotEmpty() && apkCertificates.isNotEmpty()
}
// Deliberately empty. Never infer these roots from GitHub, APKs or the development signer.
internal fun productionUpdateTrust() = UpdateTrust(emptyMap(),emptySet())
internal data class SignedUpdate(val tag: String, val version: ReleaseVersion, val code: Long, val minSdk: Int, val abis: Set<String>, val certificate: String, val asset: String, val bytes: Long, val sha256: String, val expires: Long)
internal data class ApkIdentity(val packageName: String, val versionName: String, val code: Long, val minSdk: Int, val abis: Set<String>, val certificates: Set<String>)

internal fun updateError(failure: UpdateFailure): Nothing = throw UpdateException(failure)
internal fun safeAssetName(value: String) = value.length<=120 && Regex("[A-Za-z0-9][A-Za-z0-9._-]*").matches(value) && !value.contains("..")
internal fun officialAsset(url: String, tag: String, name: String): Boolean = safeAssetName(name) &&
    url=="https://github.com/SirOtter0/INFINILECT/releases/download/$tag/$name"
internal fun safeNotes(value: String): String = value.take(16_000).filter { it=='\n' || it=='\t' || !it.isISOControl() }

/** Bound depth before recursive JSON parsing. Reject invalid UTF-8, duplicate object keys,
 * trailing data and unsupported JSON syntax. Limits apply to decoded response bytes. */
internal fun updateJson(bytes: ByteArray, limit: Int): JsonElement {
    if(bytes.size>limit) updateError(UpdateFailure.FORMAT)
    val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
    catch(_: Exception) {updateError(UpdateFailure.FORMAT)}
    var i=0
    var nodes=0
    fun ws() {while(i<text.length && text[i] in " \r\n\t") i++}
    fun string(): String {
        val start=i
        if(i>=text.length || text[i++]!='"') updateError(UpdateFailure.FORMAT)
        var escape=false
        while(i<text.length) {
            val c=text[i++]
            if(c.code<0x20) updateError(UpdateFailure.FORMAT)
            if(!escape && c=='"') return try {Json.parseToJsonElement(text.substring(start,i)).jsonPrimitive.content} catch(_: Exception) {updateError(UpdateFailure.FORMAT)}
            escape=if(escape) false else c=='\\'
        }
        updateError(UpdateFailure.FORMAT)
    }
    fun scan(depth: Int) {
        if(depth>16 || ++nodes>20_000) updateError(UpdateFailure.FORMAT)
        ws();if(i>=text.length) updateError(UpdateFailure.FORMAT)
        when(text[i]) {
            '"' -> {string()}
            '{','[' -> {
                val objectValue=text[i++]=='{';val end=if(objectValue) '}' else ']';val keys=HashSet<String>()
                ws();if(i<text.length && text[i]==end) {i++;return}
                while(true) {
                    ws();if(objectValue) {val key=string();if(key.length>256 || !keys.add(key) || keys.size>64) updateError(UpdateFailure.FORMAT);ws();if(i>=text.length || text[i++]!=':') updateError(UpdateFailure.FORMAT)}
                    scan(depth+1);ws();if(i>=text.length) updateError(UpdateFailure.FORMAT)
                    val c=text[i++];if(c==end) break;if(c!=',') updateError(UpdateFailure.FORMAT)
                }
            }
            else -> {
                val start=i;while(i<text.length && text[i] !in ",]} \r\n\t") i++
                val literal=text.substring(start,i)
                if(literal !in setOf("true","false","null") && !jsonNumber.matches(literal)) updateError(UpdateFailure.FORMAT)
            }
        }
    }
    return try {scan(0);ws();if(i!=text.length) updateError(UpdateFailure.FORMAT);Json.parseToJsonElement(text)}
    catch(e: UpdateException) {throw e} catch(_: Exception) {updateError(UpdateFailure.FORMAT)}
}
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf {it.isString}?.content ?: updateError(UpdateFailure.FORMAT)
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.takeIf {!it.isString}?.longOrNull ?: updateError(UpdateFailure.FORMAT)
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.takeIf {!it.isString}?.booleanOrNull ?: updateError(UpdateFailure.FORMAT)
internal fun JsonObject.strings(key: String): Set<String> {
    val list=get(key) as? JsonArray ?: updateError(UpdateFailure.FORMAT)
    if(list.size !in 1..4) updateError(UpdateFailure.FORMAT)
    val values=list.map {(it as? JsonPrimitive)?.takeIf {it.isString}?.content ?: updateError(UpdateFailure.FORMAT)}
    if(values.distinct().size!=values.size) updateError(UpdateFailure.FORMAT)
    return values.toSet()
}

internal fun verifyUpdateManifest(envelope: ByteArray, trust: UpdateTrust, release: OfficialRelease, installed: InstalledUpdateBuild, channel: UpdateChannel, now: Long): SignedUpdate {
    if(!trust.configured) updateError(UpdateFailure.TRUST_NOT_CONFIGURED)
    val obj=updateJson(envelope,32_768) as? JsonObject ?: updateError(UpdateFailure.FORMAT)
    if(obj.keys!=setOf("keyId","payload","signature")) updateError(UpdateFailure.FORMAT)
    val keyId=obj.text("keyId")
    if(keyId in trust.revokedKeys) updateError(UpdateFailure.SIGNATURE)
    val key=trust.manifestKeys[keyId] ?: updateError(UpdateFailure.SIGNATURE)
    val payload: ByteArray
    try {
        payload=Base64.getDecoder().decode(obj.text("payload"))
        val signature=Base64.getDecoder().decode(obj.text("signature"))
        val publicKey=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(key))
        if((publicKey as java.security.interfaces.RSAPublicKey).modulus.bitLength()<3072) updateError(UpdateFailure.SIGNATURE)
        if(!Signature.getInstance("SHA256withRSA").run {initVerify(publicKey);update(payload);verify(signature)}) updateError(UpdateFailure.SIGNATURE)
    } catch(e: UpdateException) {throw e} catch(_: GeneralSecurityException) {updateError(UpdateFailure.SIGNATURE)} catch(_: IllegalArgumentException) {updateError(UpdateFailure.SIGNATURE)}
    val p=updateJson(payload,16_384) as? JsonObject ?: updateError(UpdateFailure.FORMAT)
    if(p.keys!=setOf("schema","repository","tag","channel","package","versionCode","minSdk","abis","certificateSha256","asset","size","sha256","issuedAt","expiresAt")) updateError(UpdateFailure.FORMAT)
    if(p.number("schema")!=1L || p.text("repository")!="SirOtter0/INFINILECT" || p.text("package")!=UPDATE_PACKAGE || p.text("tag")!=release.tag) updateError(UpdateFailure.INCOMPATIBLE)
    val version=ReleaseVersion.parse(p.text("tag")) ?: updateError(UpdateFailure.FORMAT)
    val actualChannel=if(version.beta==null) UpdateChannel.STABLE else UpdateChannel.BETA
    if(p.text("channel")!=actualChannel.name.lowercase() || (channel==UpdateChannel.STABLE && actualChannel!=channel) || release.prerelease!=(version.beta!=null)) updateError(UpdateFailure.INCOMPATIBLE)
    val code=p.number("versionCode");val sdk=p.number("minSdk");val abis=p.strings("abis");val certificate=p.text("certificateSha256")
    val asset=p.text("asset");val size=p.number("size");val sha=p.text("sha256");val issued=p.number("issuedAt");val expires=p.number("expiresAt")
    if(!Regex("[0-9a-f]{64}").matches(sha) || !Regex("[0-9a-f]{64}").matches(certificate) || !safeAssetName(asset) || !asset.endsWith(".apk") || size !in 1..MAX_UPDATE_BYTES) updateError(UpdateFailure.FORMAT)
    if(issued<0 || issued>now+300_000 || expires<=now || expires<=issued || expires-issued>30L*86_400_000) updateError(UpdateFailure.SIGNATURE)
    if(certificate !in trust.apkCertificates || installed.certificates!=setOf(certificate)) updateError(UpdateFailure.SIGNATURE)
    if(code<=installed.versionCode || code>2_100_000_000 || sdk !in 26..installed.sdk.toLong() || abis.none {it in installed.abis} || abis.any {it !in setOf("arm64-v8a","armeabi-v7a","x86","x86_64")} || installed.development) updateError(UpdateFailure.INCOMPATIBLE)
    val current=ReleaseVersion.parse(installed.versionName,true) ?: updateError(UpdateFailure.INCOMPATIBLE)
    if(version<=current) updateError(UpdateFailure.INCOMPATIBLE)
    val published=release.assets.singleOrNull {it.name==asset} ?: updateError(UpdateFailure.FORMAT)
    if(published.size!=size || !officialAsset(published.url,release.tag,asset)) updateError(UpdateFailure.INTEGRITY)
    return SignedUpdate(release.tag,version,code,sdk.toInt(),abis,certificate,asset,size,sha,expires)
}

internal fun validateApk(identity: ApkIdentity, manifest: SignedUpdate, installed: InstalledUpdateBuild) {
    if(identity.packageName!=UPDATE_PACKAGE || identity.code!=manifest.code || identity.code<=installed.versionCode || ReleaseVersion.parse(identity.versionName)!=manifest.version || identity.minSdk!=manifest.minSdk || identity.minSdk>installed.sdk || identity.abis!=manifest.abis || identity.abis.intersect(installed.abis).isEmpty()) updateError(UpdateFailure.INCOMPATIBLE)
    if(identity.certificates!=setOf(manifest.certificate) || identity.certificates!=installed.certificates) updateError(UpdateFailure.SIGNATURE)
}
