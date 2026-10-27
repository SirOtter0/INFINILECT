// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal const val OFFICIAL_RELEASES_PAGE = "https://github.com/SirOtter0/INFINILECT/releases"
internal const val INSTALLED_DESKTOP_VERSION = "0.0.1-SNAPSHOT"
internal enum class UpdateChannel { STABLE, BETA }

/** Deliberately limited publishing policy; arbitrary prerelease suffixes are not betas. */
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int? = null, val snapshot: Boolean = false) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this,other,{it.major},{it.minor},{it.patch},{if(it.snapshot) -1 else it.beta ?: Int.MAX_VALUE})
    companion object {
        fun parse(value: String, installed: Boolean = false): ReleaseVersion? {
            if(value.length>70) return null
            val m=Regex("v?(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})(?:-beta\\.([1-9][0-9]{0,5})|(-SNAPSHOT))?").matchEntire(value) ?: return null
            val snapshot=m.groupValues[5].isNotEmpty()
            if(snapshot && !installed) return null
            return ReleaseVersion(m.groupValues[1].toInt(),m.groupValues[2].toInt(),m.groupValues[3].toInt(),m.groupValues[4].toIntOrNull(),snapshot)
        }
    }
}
internal data class ReleaseAsset(val name: String, val url: String, val size: Long)
internal data class OfficialRelease(val tag: String, val version: ReleaseVersion, val prerelease: Boolean, val notes: String, val assets: List<ReleaseAsset>)
internal data class InstalledUpdateBuild(val versionName: String, val versionCode: Long, val android: Boolean, val sdk: Int = 0, val abis: Set<String> = emptySet(), val certificates: Set<String> = emptySet(), val development: Boolean = false)
internal enum class UpdateFailure { CONNECTION, TIMEOUT, RATE_LIMIT, HTTP, FORMAT, REDIRECT, TRUST_NOT_CONFIGURED, SIGNATURE, INTEGRITY, INCOMPATIBLE, STORAGE, INSTALLER, PERMISSION, PREFERENCES, BUSY }
internal class UpdateException(val failure: UpdateFailure) : Exception(failure.name)
internal enum class UpdateStage { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, PERMISSION, DELEGATED, ERROR, CANCELLED }
internal data class UpdatePreferences(val channel: UpdateChannel = UpdateChannel.STABLE, val automatic: Boolean = false, val lastAttempt: Long = 0, val lastSuccess: Long = 0, val lastResult: UpdateStage = UpdateStage.IDLE, val dismissedTag: String? = null)
internal data class UpdateState(val loaded: Boolean = false, val preferences: UpdatePreferences = UpdatePreferences(), val stage: UpdateStage = UpdateStage.IDLE, val release: OfficialRelease? = null, val failure: UpdateFailure? = null, val downloaded: Long = 0, val total: Long = 0, val notice: Boolean = false, val saveFailed: Boolean = false)
/** Process-local capability: only the exact instance retained by the verifier is accepted. */
internal class PreparedUpdate
internal enum class InstallHandoff { PERMISSION, DELEGATED }
internal interface UpdateBackend {
    val installed: InstalledUpdateBuild
    val trustedInstallationConfigured: Boolean
    suspend fun loadPreferences(): UpdatePreferences
    suspend fun savePreferences(value: UpdatePreferences)
    suspend fun latest(channel: UpdateChannel): OfficialRelease?
    suspend fun prepare(release: OfficialRelease, progress: (Long,Long,Boolean) -> Unit): PreparedUpdate
    suspend fun install(value: PreparedUpdate): InstallHandoff
    suspend fun discard()
    suspend fun automaticAllowed(): Boolean
    suspend fun installationCancelled(): Boolean = false
    suspend fun installationFailure(): UpdateFailure? = null
    fun requestInstallPermission()
    fun openOfficialPage()
    fun close()
}

/** UI-thread owner. One operation, conflated preference writes, no permanent timer/service.
 * Checks only on a foreground opportunity, opt-in and at most once per 24 hours.
 * Metadata survives screen changes; preparation is explicitly screen-owned and cancelled. */
internal class UpdateController(val backend: UpdateBackend, private val clock: () -> Long, dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) {
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val mutable=MutableStateFlow(UpdateState())
    val state=mutable.asStateFlow()
    private val saves=Channel<Unit>(Channel.CONFLATED)
    private var operation: Job?=null
    private val tickets=MutableStateFlow(0)
    private var generation: Int
        get()=tickets.value
        set(value) {tickets.value=value}
    private var ready: PreparedUpdate?=null
    private var foreground=false
    private var closed=false
    private var shutdown: Job?=null
    private val writer=scope.launch {
        try {
            val p=backend.loadPreferences()
            mutable.value=state.value.copy(loaded=true,preferences=p,stage=if(p.lastResult==UpdateStage.CHECKING) UpdateStage.CANCELLED else p.lastResult.takeIf {it in setOf(UpdateStage.CURRENT,UpdateStage.ERROR,UpdateStage.AVAILABLE,UpdateStage.CANCELLED)} ?: UpdateStage.IDLE)
        } catch(e: CancellationException) {throw e} catch(_: Exception) {mutable.value=state.value.copy(loaded=true,saveFailed=true)}
        if(foreground) foreground()
        for(ignored in saves) {
            try {backend.savePreferences(state.value.preferences);mutable.update {it.copy(saveFailed=false)}}
            catch(e: CancellationException) {throw e} catch(_: Exception) {mutable.update {it.copy(saveFailed=true)}}
        }
    }
    private fun preferences(value: UpdatePreferences) {mutable.update {it.copy(preferences=value)};saves.trySend(Unit)}
    fun channel(value: UpdateChannel) {
        if(!state.value.loaded || value==state.value.preferences.channel) return
        cancel();preferences(state.value.preferences.copy(channel=value,dismissedTag=null,lastAttempt=0,lastResult=UpdateStage.IDLE))
        mutable.update {it.copy(stage=UpdateStage.IDLE,release=null,notice=false,failure=null)}
    }
    fun automatic(value: Boolean) {if(state.value.loaded) preferences(state.value.preferences.copy(automatic=value))}
    fun later() {val tag=state.value.release?.tag ?: return;preferences(state.value.preferences.copy(dismissedTag=tag));mutable.update {it.copy(notice=false)}}
    fun foreground() {
        if(closed) return
        foreground=true
        scope.launch {
          try {
            if(backend.installationCancelled()) mutable.update {it.copy(stage=UpdateStage.CANCELLED)}
            backend.installationFailure()?.let {fail(it)}
            val p=state.value.preferences;val now=clock()
            if(foreground && state.value.loaded && p.automatic && operation?.isActive!=true &&
                (p.lastAttempt==0L || now-p.lastAttempt>=86_400_000L || p.lastAttempt>now+300_000) && backend.automaticAllowed()) check(true)
          } catch(e: CancellationException) {throw e} catch(e: UpdateException) {fail(e.failure)} catch(_: Exception) {fail(UpdateFailure.CONNECTION)}
        }
    }
    fun background() {foreground=false;if(state.value.stage in setOf(UpdateStage.CHECKING,UpdateStage.DOWNLOADING,UpdateStage.VERIFYING)) cancel()}
    fun check(automatic: Boolean = false) {
        if(closed || !state.value.loaded || operation?.isActive==true) return
        val ticket=++generation;val channel=state.value.preferences.channel
        preferences(state.value.preferences.copy(lastAttempt=clock(),lastResult=UpdateStage.CHECKING))
        mutable.update {it.copy(stage=UpdateStage.CHECKING,failure=null,notice=false)}
        operation=scope.launch {
            try {
                backend.discard();ready=null
                val release=backend.latest(channel);ensureActive();if(ticket!=generation) return@launch
                val stage=if(release==null) UpdateStage.CURRENT else UpdateStage.AVAILABLE
                preferences(state.value.preferences.copy(lastSuccess=clock(),lastResult=stage))
                mutable.update {it.copy(stage=stage,release=release,notice=automatic && release!=null && it.preferences.dismissedTag!=release.tag)}
            } catch(e: CancellationException) {throw e}
            catch(e: UpdateException) {if(ticket==generation) fail(e.failure,true)}
            catch(_: Exception) {if(ticket==generation) fail(UpdateFailure.CONNECTION,true)}
        }
    }
    private fun fail(reason: UpdateFailure, check: Boolean = false) {if(check) preferences(state.value.preferences.copy(lastResult=UpdateStage.ERROR));mutable.update {it.copy(stage=UpdateStage.ERROR,failure=reason,notice=false)}}
    fun download() {
        val release=state.value.release ?: return
        if(closed || operation?.isActive==true) return
        if(!backend.trustedInstallationConfigured) {fail(UpdateFailure.TRUST_NOT_CONFIGURED);return}
        val ticket=++generation
        mutable.update {it.copy(stage=UpdateStage.DOWNLOADING,failure=null,downloaded=0,total=0)}
        operation=scope.launch {
            try {
                val value=backend.prepare(release) {done,total,verifying ->
                    if(ticket==generation) mutable.update {it.copy(stage=if(verifying) UpdateStage.VERIFYING else UpdateStage.DOWNLOADING,downloaded=done,total=total)}
                }
                ensureActive();if(ticket!=generation) return@launch
                ready=value;mutable.update {it.copy(stage=UpdateStage.READY)}
            } catch(e: CancellationException) {throw e}
            catch(e: UpdateException) {if(ticket==generation) fail(e.failure)}
            catch(_: Exception) {if(ticket==generation) fail(UpdateFailure.STORAGE)}
            finally {if(ticket!=generation || state.value.stage!=UpdateStage.READY) withContext(NonCancellable) {discardSafely()}}
        }
    }
    fun install() {
        val value=ready ?: return
        if(closed || operation?.isActive==true) return
        val ticket=++generation
        operation=scope.launch {
            try {
                val result=backend.install(value);ensureActive();if(ticket!=generation) return@launch
                mutable.update {it.copy(stage=if(result==InstallHandoff.PERMISSION) UpdateStage.PERMISSION else UpdateStage.DELEGATED)}
                if(result==InstallHandoff.DELEGATED) ready=null
            } catch(e: CancellationException) {throw e}
            catch(e: UpdateException) {if(ticket==generation) fail(e.failure)}
            catch(_: Exception) {if(ticket==generation) fail(UpdateFailure.INSTALLER)}
        }
    }
    fun permission() {if(ready!=null && state.value.stage==UpdateStage.PERMISSION) try {backend.requestInstallPermission()} catch(_: Exception) {fail(UpdateFailure.PERMISSION)}}
    fun cancel() {
        if(state.value.stage==UpdateStage.CHECKING) preferences(state.value.preferences.copy(lastResult=UpdateStage.CANCELLED))
        generation++;val previous=operation;previous?.cancel();ready=null
        mutable.update {it.copy(stage=UpdateStage.CANCELLED,notice=false)}
        operation=scope.launch {previous?.join();discardSafely()}
    }
    fun leaveScreen() {if(state.value.stage in setOf(UpdateStage.DOWNLOADING,UpdateStage.VERIFYING,UpdateStage.READY,UpdateStage.PERMISSION)) cancel()}
    fun openOfficialPage() {try {backend.openOfficialPage()} catch(_: Exception) {fail(UpdateFailure.INSTALLER)}}
    private suspend fun discardSafely() {
        try {backend.discard()}
        catch(e: CancellationException) {throw e}
        catch(e: UpdateException) {mutable.update {it.copy(stage=UpdateStage.ERROR,failure=e.failure)}}
        catch(_: Exception) {mutable.update {it.copy(stage=UpdateStage.ERROR,failure=UpdateFailure.STORAGE)}}
    }
    fun close() {
        if(!closed) {
            closed=true;generation++;val previous=operation;previous?.cancel();saves.close()
            shutdown=scope.launch {
                previous?.join();writer.join();withContext(NonCancellable) {discardSafely()}
                try {backend.close()} catch(e: UpdateException) {mutable.update {it.copy(stage=UpdateStage.ERROR,failure=e.failure)}}
                finally {scope.cancel()}
            }
        }
    }
    suspend fun awaitClosed() {shutdown?.join()}
}
