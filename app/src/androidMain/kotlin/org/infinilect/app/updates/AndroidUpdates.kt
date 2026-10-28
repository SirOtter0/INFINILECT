// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import android.app.PendingIntent
import android.content.*
import android.content.pm.*
import android.net.*
import android.os.*
import android.provider.Settings
import kotlinx.coroutines.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.zip.ZipFile

internal class AndroidUpdateInstaller(context: Context) : UpdateInstaller {
    private val context=context.applicationContext
    private val pm=this.context.packageManager
    // Read this app's merged manifest, not remote metadata or the current user grant.
    // Adding trust roots alone must not enable an installer in a permission-free APK.
    @Suppress("DEPRECATION")
    override val installationSupported: Boolean = pm.getPackageInfo(this.context.packageName,PackageManager.GET_PERMISSIONS)
        .requestedPermissions?.contains(android.Manifest.permission.REQUEST_INSTALL_PACKAGES)==true
    @Suppress("DEPRECATION")
    private fun info(file: Path? = null): PackageInfo {
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return (if(file==null) pm.getPackageInfo(context.packageName,flags) else pm.getPackageArchiveInfo(file.toString(),flags)) ?: updateError(UpdateFailure.INCOMPATIBLE)
    }
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map {MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") {b -> "%02x".format(b)}}?.toSet().orEmpty()
    }
    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo)=if(Build.VERSION.SDK_INT>=28) info.longVersionCode else info.versionCode.toLong()
    override val installed: InstalledUpdateBuild get() {
        val p=info()
        return InstalledUpdateBuild(p.versionName.orEmpty(),code(p),true,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toSet(),certificates(p),
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)!=0)
    }
    override suspend fun inspect(file: Path): ApkIdentity = withContext(Dispatchers.IO) {
        val p=info(file);val abis=linkedSetOf<String>()
        // Native content is inspected, never extracted or loaded. APK parsing is system-owned.
        ZipFile(file.toFile()).use {zip ->
            val entries=zip.entries();var count=0
            while(entries.hasMoreElements()) {
                ensureActive();val name=entries.nextElement().name
                if(++count>50_000 || name.length>512) updateError(UpdateFailure.FORMAT)
                if(name.startsWith("lib/") && name.endsWith(".so")) {
                    val parts=name.split('/');if(parts.size!=3 || parts[1] !in setOf("arm64-v8a","armeabi-v7a","x86","x86_64")) updateError(UpdateFailure.INCOMPATIBLE)
                    abis+=parts[1]
                }
            }
        }
        ApkIdentity(p.packageName,p.versionName.orEmpty(),code(p),p.applicationInfo?.minSdkVersion ?: updateError(UpdateFailure.INCOMPATIBLE),abis,certificates(p))
    }
    override suspend fun handoff(file: Path): InstallHandoff = withContext(Dispatchers.IO) {
        if(!installationSupported) updateError(UpdateFailure.TRUST_NOT_CONFIGURED)
        if(!pm.canRequestPackageInstalls()) return@withContext InstallHandoff.PERMISSION
        val service=pm.packageInstaller
        val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(UPDATE_PACKAGE)
            if(Build.VERSION.SDK_INT>=31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            setSize(Files.size(file))
        }
        val id=service.createSession(params);var committed=false
        try {
            service.openSession(id).use {session ->
                session.openWrite("base.apk",0,Files.size(file)).use {out ->
                    Files.newInputStream(file).use {input -> val b=ByteArray(32_768);while(true) {ensureActive();val n=input.read(b);if(n<0) break;out.write(b,0,n)}}
                    session.fsync(out)
                }
                ensureActive()
                val callback=Intent(context,UpdateInstallReceiver::class.java).setAction(INSTALL_CALLBACK)
                val flags=PendingIntent.FLAG_UPDATE_CURRENT or (if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0)
                val sender=PendingIntent.getBroadcast(context,id,callback,flags)
                context.getSharedPreferences(INSTALL_STATUS,Context.MODE_PRIVATE).edit().putInt("session",id).putInt("status",PackageInstaller.STATUS_PENDING_USER_ACTION).apply()
                session.commit(sender.intentSender);committed=true
            }
            InstallHandoff.DELEGATED
        } finally {if(!committed) service.abandonSession(id)}
    }
    override fun requestPermission() {
        if(!installationSupported) updateError(UpdateFailure.TRUST_NOT_CONFIGURED)
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun openOfficialPage() {context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(OFFICIAL_RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
    override suspend fun automaticAllowed(): Boolean = withContext(Dispatchers.IO) {
        val power=context.getSystemService(PowerManager::class.java)
        if(power.isPowerSaveMode) return@withContext false
        val network=context.getSystemService(ConnectivityManager::class.java)
        val caps=network.getNetworkCapabilities(network.activeNetwork) ?: return@withContext false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    override suspend fun installationCancelled(): Boolean = withContext(Dispatchers.IO) {
        val prefs=context.getSharedPreferences(INSTALL_STATUS,Context.MODE_PRIVATE)
        val status=prefs.getInt("status",Int.MIN_VALUE)
        if(status==PackageInstaller.STATUS_FAILURE_ABORTED) {prefs.edit().remove("status").apply();true} else false
    }
    override suspend fun installationFailure(): UpdateFailure? = withContext(Dispatchers.IO) {
        val prefs=context.getSharedPreferences(INSTALL_STATUS,Context.MODE_PRIVATE)
        when(prefs.getInt("status",Int.MIN_VALUE)) {
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> UpdateFailure.INCOMPATIBLE
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateFailure.STORAGE
            PackageInstaller.STATUS_FAILURE,PackageInstaller.STATUS_FAILURE_BLOCKED,PackageInstaller.STATUS_FAILURE_CONFLICT,PackageInstaller.STATUS_FAILURE_INVALID -> UpdateFailure.INSTALLER
            else -> null
        }.also {if(it!=null) prefs.edit().remove("status").apply()}
    }
}
private const val INSTALL_STATUS="update-install-v1"
private const val INSTALL_CALLBACK="org.infinilect.app.UPDATE_INSTALL_STATUS"

/** Explicit, non-exported receiver. Only the system-held session PendingIntent supplies
 * the confirmation Intent. No release string becomes an Intent, URI or install parameter. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action!=INSTALL_CALLBACK) return
        val prefs=context.getSharedPreferences(INSTALL_STATUS,Context.MODE_PRIVATE)
        if(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-2)!=prefs.getInt("session",-1)) return
        val status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)
        prefs.edit().putInt("status",status).apply()
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation=if(Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java) else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            try {context.startActivity((confirmation ?: return).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            catch(_: ActivityNotFoundException) {prefs.edit().putInt("status",PackageInstaller.STATUS_FAILURE).apply()}
            catch(_: SecurityException) {prefs.edit().putInt("status",PackageInstaller.STATUS_FAILURE).apply()}
        }
        // STATUS_SUCCESS is not announced as an installed version. The next process reads
        // actual PackageManager version identity; cancellation is surfaced on foreground.
    }
}

internal fun createAndroidUpdates(context: Context): UpdateController {
    val app=context.applicationContext
    return UpdateController(GitHubUpdates(app.cacheDir.toPath().resolve("updates-v1"),AndroidUpdateInstaller(app),preferencesDirectory=app.filesDir.toPath().resolve("updates-v1")),System::currentTimeMillis)
}
