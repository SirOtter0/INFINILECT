// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import java.security.*
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.test.*

internal object SyntheticUpdates {
    val keys: KeyPair by lazy {KeyPairGenerator.getInstance("RSA").apply {initialize(3072)}.generateKeyPair()}
    const val now=1_700_000_000_000L
    const val cert="1111111111111111111111111111111111111111111111111111111111111111"
    val installed=InstalledUpdateBuild("1.0.0",1,true,35,setOf("arm64-v8a"),setOf(cert))
    val trust get()=UpdateTrust(mapOf("test-only" to keys.public.encoded),setOf(cert))
    val bytes="Original synthetic fixture, not an Android APK".toByteArray()
    val hash get()=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
    fun payload(change: (MutableMap<String,JsonElement>)->Unit = {}): JsonObject {
        val map=buildJsonObject {
            put("schema",1);put("repository","SirOtter0/INFINILECT");put("tag","v1.1.0");put("channel","stable");put("package",UPDATE_PACKAGE)
            put("versionCode",2);put("minSdk",26);putJsonArray("abis") {add("arm64-v8a")};put("certificateSha256",cert)
            put("asset","infinilect-v1.1.0-universal.apk");put("size",bytes.size);put("sha256",hash);put("issuedAt",now-1000);put("expiresAt",now+86_400_000)
        }.toMutableMap();change(map);return JsonObject(map)
    }
    fun envelope(payload: JsonObject = payload()): ByteArray {
        val data=payload.toString().toByteArray()
        val sig=Signature.getInstance("SHA256withRSA").run {initSign(keys.private);update(data);sign()}
        return buildJsonObject {put("keyId","test-only");put("payload",Base64.getEncoder().encodeToString(data));put("signature",Base64.getEncoder().encodeToString(sig))}.toString().toByteArray()
    }
    fun release(envelope: ByteArray = envelope()): OfficialRelease = OfficialRelease("v1.1.0",ReleaseVersion(1,1,0),false,"Synthetic notes",listOf(
        ReleaseAsset("infinilect-update-v1.json","https://github.com/SirOtter0/INFINILECT/releases/download/v1.1.0/infinilect-update-v1.json",envelope.size.toLong()),
        ReleaseAsset("infinilect-v1.1.0-universal.apk","https://github.com/SirOtter0/INFINILECT/releases/download/v1.1.0/infinilect-v1.1.0-universal.apk",bytes.size.toLong())))
    fun identity()=ApkIdentity(UPDATE_PACKAGE,"1.1.0",2,26,setOf("arm64-v8a"),setOf(cert))
}

class UpdateTrustTest {
    private fun verify(data: ByteArray = SyntheticUpdates.envelope(),trust: UpdateTrust = SyntheticUpdates.trust,installed: InstalledUpdateBuild = SyntheticUpdates.installed,release: OfficialRelease = SyntheticUpdates.release(data)) =
        verifyUpdateManifest(data,trust,release,installed,UpdateChannel.STABLE,SyntheticUpdates.now)
    private fun rejected(reason: UpdateFailure,block: ()->Unit) {assertEquals(reason,assertFailsWith<UpdateException>(block=block).failure)}
    @Test fun productionRootIsEmptyAndNoDevelopmentKeyCanEnableIt() {
        assertFalse(productionUpdateTrust().configured)
        rejected(UpdateFailure.TRUST_NOT_CONFIGURED) {verify(trust=productionUpdateTrust())}
        rejected(UpdateFailure.INCOMPATIBLE) {verify(installed=SyntheticUpdates.installed.copy(development=true))}
    }
    @Test fun realSyntheticRsaSignatureBindsAllFieldsAndExpiry() {
        val m=verify();assertEquals(2,m.code);assertEquals(SyntheticUpdates.hash,m.sha256)
        rejected(UpdateFailure.SIGNATURE) {verify(trust=SyntheticUpdates.trust.copy(revokedKeys=setOf("test-only")))}
        rejected(UpdateFailure.SIGNATURE) {verify(trust=SyntheticUpdates.trust.copy(manifestKeys=mapOf("other" to SyntheticUpdates.keys.public.encoded)))}
        val original=updateJson(SyntheticUpdates.envelope(),32768).jsonObject.toMutableMap()
        original["payload"]=JsonPrimitive(Base64.getEncoder().encodeToString(SyntheticUpdates.payload {it["versionCode"]=JsonPrimitive(3)}.toString().toByteArray()))
        rejected(UpdateFailure.SIGNATURE) {verify(JsonObject(original).toString().toByteArray())}
        for(change in listOf<(MutableMap<String,JsonElement>)->Unit>(
            {it["expiresAt"]=JsonPrimitive(SyntheticUpdates.now)},
            {it["issuedAt"]=JsonPrimitive(SyntheticUpdates.now+300_001)},
            {it["expiresAt"]=JsonPrimitive(SyntheticUpdates.now+31L*86_400_000)})) {
            rejected(UpdateFailure.SIGNATURE) {verify(SyntheticUpdates.envelope(SyntheticUpdates.payload(change)))}
        }
    }
    @Test fun wrongPackageRepositoryCodeArchitectureCertificateAndAbiAreRejected() {
        for((field,value) in listOf("repository" to JsonPrimitive("fork/INFINILECT"),"package" to JsonPrimitive("other.app"),"versionCode" to JsonPrimitive(1),"minSdk" to JsonPrimitive(36),"abis" to JsonArray(listOf(JsonPrimitive("x86"))))) {
            rejected(UpdateFailure.INCOMPATIBLE) {verify(SyntheticUpdates.envelope(SyntheticUpdates.payload {it[field]=value}))}
        }
        rejected(UpdateFailure.SIGNATURE) {verify(installed=SyntheticUpdates.installed.copy(certificates=setOf("2".repeat(64))))}
        rejected(UpdateFailure.SIGNATURE) {verify(SyntheticUpdates.envelope(SyntheticUpdates.payload {it["certificateSha256"]=JsonPrimitive("2".repeat(64))}))}
        rejected(UpdateFailure.INCOMPATIBLE) {verify(installed=SyntheticUpdates.installed.copy(versionName="2.0.0"))}
        rejected(UpdateFailure.INTEGRITY) {verify(release=SyntheticUpdates.release().let {it.copy(assets=it.assets.map {a -> if(a.name.endsWith(".apk")) a.copy(size=a.size+1) else a})})}
    }
    @Test fun apkIdentityMustAgreeWithSignedManifestAndCurrentInstallation() {
        val m=verify();val good=SyntheticUpdates.identity();validateApk(good,m,SyntheticUpdates.installed)
        for(identity in listOf(good.copy(packageName="wrong"),good.copy(code=1),good.copy(versionName="2.0.0"),good.copy(minSdk=27),good.copy(abis=setOf("x86")))) {
            rejected(UpdateFailure.INCOMPATIBLE) {validateApk(identity,m,SyntheticUpdates.installed)}
        }
        rejected(UpdateFailure.SIGNATURE) {validateApk(good.copy(certificates=setOf("2".repeat(64))),m,SyntheticUpdates.installed)}
    }
    @Test fun signedBetaMustMatchTheExplicitChannelAndReleaseFlag() {
        val tag="v1.2.0-beta.1";val name="infinilect-v1.2.0-beta.1-universal.apk"
        val data=SyntheticUpdates.envelope(SyntheticUpdates.payload {it["tag"]=JsonPrimitive(tag);it["channel"]=JsonPrimitive("beta");it["asset"]=JsonPrimitive(name)})
        val release=SyntheticUpdates.release(data).copy(tag=tag,version=ReleaseVersion(1,2,0,1),prerelease=true,assets=listOf(ReleaseAsset(name,"https://github.com/SirOtter0/INFINILECT/releases/download/$tag/$name",SyntheticUpdates.bytes.size.toLong())))
        rejected(UpdateFailure.INCOMPATIBLE) {verifyUpdateManifest(data,SyntheticUpdates.trust,release,SyntheticUpdates.installed,UpdateChannel.STABLE,SyntheticUpdates.now)}
        assertEquals(ReleaseVersion(1,2,0,1),verifyUpdateManifest(data,SyntheticUpdates.trust,release,SyntheticUpdates.installed,UpdateChannel.BETA,SyntheticUpdates.now).version)
        rejected(UpdateFailure.INCOMPATIBLE) {verifyUpdateManifest(data,SyntheticUpdates.trust,release.copy(prerelease=false),SyntheticUpdates.installed,UpdateChannel.BETA,SyntheticUpdates.now)}
    }
    @Test fun urlsNamesJsonAndResourceLimitsFailClosed() {
        val url=SyntheticUpdates.release().assets.last().url
        assertTrue(officialAsset(url,"v1.1.0","infinilect-v1.1.0-universal.apk"))
        for(s in listOf("../x.apk","..","a/b.apk","%2e%2e.apk","/x.apk","a\\b.apk")) assertFalse(safeAssetName(s),s)
        assertFalse(officialAsset(url.replace("SirOtter0","fork"),"v1.1.0","infinilect-v1.1.0-universal.apk"))
        for(data in listOf("{\"x\":1,\"x\":2}".toByteArray(),"[".repeat(20).toByteArray(),byteArrayOf(0xc3.toByte(),0x28),"{} {}".toByteArray(),"{\"x\":NaN}".toByteArray())) rejected(UpdateFailure.FORMAT) {updateJson(data,32768)}
        rejected(UpdateFailure.FORMAT) {updateJson(ByteArray(32769),32768)}
        rejected(UpdateFailure.FORMAT) {updateJson(("["+List(20_001) {"0"}.joinToString(",")+"]").toByteArray(),100_000)}
        rejected(UpdateFailure.FORMAT) {verify(SyntheticUpdates.envelope(SyntheticUpdates.payload {it["size"]=JsonPrimitive(MAX_UPDATE_BYTES+1)}))}
        rejected(UpdateFailure.FORMAT) {verify(SyntheticUpdates.envelope(SyntheticUpdates.payload {it["command"]=JsonPrimitive("arbitrary")}))}
        assertEquals("<script>x</script>\n",safeNotes("<script>x</script>\n\u0000")) // Plain data, never executed/rendered as HTML.
        assertEquals(16000,safeNotes("x".repeat(20000)).length)
    }
}
