// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    private class Backend : UpdateBackend {
        override val installed=InstalledUpdateBuild("1.0.0",1,true)
        override var trustedInstallationConfigured=true
        var preferences=UpdatePreferences();var checks=0;var downloads=0;var discarded=0;var closed=false;var allowed=true
        var load: suspend ()->Unit = {};var query: suspend ()->Unit = {};var downloading: suspend ((Long,Long,Boolean)->Unit)->Unit = {}
        var outcome=InstallHandoff.DELEGATED;var cancelled=false;var permissions=0;var failSave=false
        override suspend fun loadPreferences(): UpdatePreferences {load();return preferences}
        override suspend fun savePreferences(value: UpdatePreferences) {if(failSave) throw UpdateException(UpdateFailure.PREFERENCES);preferences=value}
        override suspend fun latest(channel: UpdateChannel): OfficialRelease {checks++;query();return release()}
        override suspend fun prepare(release: OfficialRelease,progress: (Long,Long,Boolean)->Unit): PreparedUpdate {downloads++;downloading(progress);return PreparedUpdate()}
        override suspend fun install(value: PreparedUpdate)=outcome
        override suspend fun discard() {discarded++}
        override suspend fun automaticAllowed()=allowed
        override suspend fun installationCancelled()=cancelled.also {cancelled=false}
        override fun requestInstallPermission() {permissions++}
        override fun openOfficialPage()=Unit
        override fun close() {closed=true}
    }
    @Test fun versionsAreStructuredAndOnlyExplicitBetaTagsQualify() {
        assertTrue(ReleaseVersion.parse("v1.10.0")!!>ReleaseVersion.parse("1.9.99")!!)
        assertTrue(ReleaseVersion.parse("1.1.0")!!>ReleaseVersion.parse("1.1.0-beta.9")!!)
        assertTrue(ReleaseVersion.parse("1.1.0-beta.10")!!>ReleaseVersion.parse("1.1.0-beta.2")!!)
        for(s in listOf("01.0.0","1.0","1.0.0-beta","1.0.0-rc.1","1.0.0-beta.0","1.0.0+build","1.0.0-SNAPSHOT","1.0.0/../../")) assertNull(ReleaseVersion.parse(s),s)
        assertNotNull(ReleaseVersion.parse("0.0.1-SNAPSHOT",true))
    }
    @Test fun loadingPreferencesDoesNotStartNetworkOrOverwriteExistingChoices()=runTest {
        val b=Backend();val barrier=CompletableDeferred<Unit>();b.load={barrier.await()};b.preferences=UpdatePreferences(UpdateChannel.BETA)
        val c=UpdateController(b,{1_000},StandardTestDispatcher(testScheduler));c.check();c.channel(UpdateChannel.STABLE);runCurrent()
        assertEquals(0,b.checks);barrier.complete(Unit);runCurrent();assertEquals(UpdateChannel.BETA,c.state.value.preferences.channel)
        c.check();c.check();runCurrent();assertEquals(1,b.checks);assertEquals(UpdateStage.AVAILABLE,c.state.value.stage)
        c.close();c.awaitClosed();assertTrue(b.closed)
    }
    @Test fun automaticIsOptInBoundedForegroundOnlyAndDismissalPersists()=runTest {
        var now=100_000L;val b=Backend();val c=UpdateController(b,{now},StandardTestDispatcher(testScheduler));runCurrent()
        c.foreground();runCurrent();assertEquals(0,b.checks)
        c.automatic(true);b.allowed=false;c.foreground();runCurrent();assertEquals(0,b.checks)
        b.allowed=true;c.foreground();c.foreground();runCurrent();assertEquals(1,b.checks);assertTrue(c.state.value.notice)
        c.later();runCurrent();assertEquals("v1.1.0",b.preferences.dismissedTag);assertFalse(c.state.value.notice)
        repeat(20) {c.foreground()};runCurrent();assertEquals(1,b.checks)
        now+=86_400_001;c.foreground();runCurrent();assertEquals(2,b.checks);assertFalse(c.state.value.notice)
        c.background();now+=86_400_001;runCurrent();assertEquals(2,b.checks)
        c.close();c.awaitClosed()
    }
    @Test fun downloadsRequireTrustAndExplicitActionAndRejectStaleCompletion()=runTest {
        val b=Backend();val c=UpdateController(b,{1_000},StandardTestDispatcher(testScheduler));runCurrent();c.check();runCurrent();assertEquals(0,b.downloads)
        b.trustedInstallationConfigured=false;c.download();runCurrent();assertEquals(UpdateFailure.TRUST_NOT_CONFIGURED,c.state.value.failure);assertEquals(0,b.downloads)
        b.trustedInstallationConfigured=true
        val barrier=CompletableDeferred<Unit>();b.downloading={progress -> withContext(NonCancellable) {barrier.await();progress(99,100,true)}}
        c.download();runCurrent();repeat(20) {c.download()};assertEquals(1,b.downloads)
        c.leaveScreen();runCurrent();assertEquals(UpdateStage.CANCELLED,c.state.value.stage)
        c.check();runCurrent();assertEquals(1,b.checks,"An uncancellable old operation cannot create an unbounded replacement queue")
        barrier.complete(Unit);runCurrent();assertEquals(UpdateStage.CANCELLED,c.state.value.stage)
        c.check();runCurrent();assertEquals(2,b.checks);assertEquals(UpdateStage.AVAILABLE,c.state.value.stage)
        c.close();c.awaitClosed()
    }
    @Test fun cooperativeCancellationRetryAndInstallerPermissionRemainExplicit()=runTest {
        val b=Backend();val c=UpdateController(b,{1_000},StandardTestDispatcher(testScheduler));runCurrent();c.check();runCurrent()
        b.downloading={awaitCancellation()};c.download();runCurrent();c.background();runCurrent();assertEquals(UpdateStage.CANCELLED,c.state.value.stage)
        b.downloading={it(100,100,true)};c.download();runCurrent();assertEquals(UpdateStage.READY,c.state.value.stage)
        b.outcome=InstallHandoff.PERMISSION;c.install();runCurrent();assertEquals(UpdateStage.PERMISSION,c.state.value.stage);assertEquals(0,b.permissions)
        c.permission();assertEquals(1,b.permissions)
        b.outcome=InstallHandoff.DELEGATED;c.install();runCurrent();assertEquals(UpdateStage.DELEGATED,c.state.value.stage)
        b.cancelled=true;c.foreground();runCurrent();assertEquals(UpdateStage.CANCELLED,c.state.value.stage)
        c.close();c.awaitClosed()
    }
    @Test fun providerAndPreferenceFailuresAreVisibleAndRecoverable()=runTest {
        val b=Backend();val c=UpdateController(b,{1_000},StandardTestDispatcher(testScheduler));runCurrent()
        b.query={throw UpdateException(UpdateFailure.RATE_LIMIT)};b.failSave=true;c.check();runCurrent()
        assertEquals(UpdateFailure.RATE_LIMIT,c.state.value.failure);assertTrue(c.state.value.saveFailed)
        b.query={};b.failSave=false;c.check();runCurrent();assertEquals(UpdateStage.AVAILABLE,c.state.value.stage);assertFalse(c.state.value.saveFailed)
        c.close();c.awaitClosed()
    }
    @Test fun restartingRetainsTheLastCheckResultAndMarksInterruptedChecksCancelled()=runTest {
        for(result in listOf(UpdateStage.AVAILABLE,UpdateStage.CURRENT,UpdateStage.ERROR,UpdateStage.CHECKING)) {
            val b=Backend();b.preferences=UpdatePreferences(UpdateChannel.BETA,true,900,800,result,"v1.1.0")
            val c=UpdateController(b,{1_000},StandardTestDispatcher(testScheduler));runCurrent()
            assertEquals(if(result==UpdateStage.CHECKING) UpdateStage.CANCELLED else result,c.state.value.stage)
            assertEquals(900L,c.state.value.preferences.lastAttempt);assertEquals(UpdateChannel.BETA,c.state.value.preferences.channel)
            assertNull(c.state.value.release,"No saved discovery metadata becomes a prepared install")
            assertEquals(0,b.checks);assertEquals(0,b.downloads);c.close();c.awaitClosed()
        }
    }
    companion object {
        private fun release()=OfficialRelease("v1.1.0",ReleaseVersion(1,1,0),false,"Synthetic notes",emptyList())
    }
}
