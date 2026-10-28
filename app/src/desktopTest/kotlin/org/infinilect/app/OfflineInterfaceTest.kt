// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.downloads.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import org.jetbrains.compose.resources.*
import java.util.Locale
import kotlin.test.*

/** Shared production controls rendered headlessly, never physical Android evidence. */
@OptIn(ExperimentalComposeUiApi::class,InternalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class OfflineInterfaceTest {
    private class Backend: DownloadBackend {
        val id=PublicationId(SourceId("synthetic-offline-ui"),"same-original-id")
        val r=PublicationResource(id,"original",PublicationFormat.TEXT,"text/plain")
        val p=Publication(id,"Original Unicode 🦦 title with a very long unchanged publication name",PublicationType.BOOK,resources=listOf(r))
        var row: DownloadEntry?=null;var revision=0L;var opens=0;var removed=0
        var transferFailure: DownloadFailure?=null;var offerFailure: DownloadFailure?=null
        val gate=CompletableDeferred<Unit>()
        override fun capability(id: PublicationId)=DownloadCapability.AUTHORIZED_SOURCE
        override suspend fun load()=snapshot()
        override suspend fun snapshot()=DownloadSnapshot(listOfNotNull(row),revision=revision)
        override suspend fun offers(id: PublicationId): List<DownloadOffer> {
            offerFailure?.let {throw DownloadException(it)}
            return listOf(DownloadOffer(p,r,1000))
        }
        override suspend fun pending(offer: DownloadOffer)=DownloadEntry(p,r.format,DownloadStatus.PENDING,r,1000,revision=++revision).also {row=it}
        override suspend fun download(entry: DownloadEntry,started: suspend (DownloadEntry)->Unit,progress: suspend (DownloadProgress)->Unit): DownloadEntry {
            opens++;row=entry.copy(status=DownloadStatus.DOWNLOADING,revision=++revision);started(row!!);progress(DownloadProgress(500,1000));gate.await()
            transferFailure?.let {throw DownloadException(it)}
            return row!!.copy(status=DownloadStatus.DOWNLOADED,revision=++revision).also {row=it}
        }
        override suspend fun settle(id: PublicationId,status: DownloadStatus,failure: DownloadFailure?)=row?.copy(status=status,failure=failure,revision=++revision).also {row=it}
        override suspend fun acquire(id: PublicationId): OfflineReadingCopy?=null
        override suspend fun remove(id: PublicationId) {removed++;row=null;revision++}
        override suspend fun quota(bytes: Long)=Unit
        override suspend fun close()=Unit
    }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun walk(n: SemanticsNode): List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
    }
    private fun has(n: SemanticsNode,label: String): Boolean = n.config.getOrNull(SemanticsProperties.Text)?.any {it.text==label}==true || n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true || n.children.any {has(it,label)}
    private fun TestScope.draw(scene: ImageComposeScene) {repeat(10) {runCurrent();scene.render(testScheduler.currentTime*1_000_000).close()};runCurrent()}
    private fun TestScope.click(scene: ImageComposeScene,label: String) {
        val n=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Disabled) && has(it,label)}
        assertTrue(n.config[SemanticsActions.OnClick].action!!.invoke());draw(scene)
    }
    @Test fun transferFailureIsVisibleInDetailsAndRetryCanRecoverInAllThreeLanguages()=runTest {
        deviceInterfaceLanguage();val previous=Locale.getDefault()
        val tables=try {interfaceLanguages.associateWith {language ->applyInterfaceLanguage(language);loadInterfaceStrings(getSystemResourceEnvironment())}} finally {Locale.setDefault(previous)}
        for((_,s) in tables) {
            val b=Backend().also {it.transferFailure=DownloadFailure.HTTP}
            val manager=OfflineDownloads(b,StandardTestDispatcher(testScheduler))
            val scene=ImageComposeScene(320,540,Density(1f,1.8f),coroutineContext=StandardTestDispatcher(testScheduler))
            try {
                scene.setContent {CompositionLocalProvider(LocalAppStrings provides s) {ApplicationTheme(ApplicationThemeMode.DARK) {Column {
                    DownloadAction(b.p,manager);DownloadFailureMessage(b.id,manager)
                }}}}
                draw(scene);click(scene,s[UiText.DL_DOWNLOAD]);click(scene,s[UiText.DL_DOWNLOAD])
                b.gate.complete(Unit);draw(scene)
                assertEquals(DownloadStatus.ERROR,b.row?.status)
                assertTrue(nodes(scene).any {it.config.getOrNull(SemanticsProperties.Text)?.any {text ->text.text==s[UiText.DL_HTTP_ERROR]}==true})
                assertTrue(nodes(scene).any {it.config.getOrNull(SemanticsProperties.StateDescription)==s[UiText.DL_HTTP_ERROR]})
                b.transferFailure=null;click(scene,s[UiText.DL_RETRY]);click(scene,s[UiText.DL_DOWNLOAD])
                assertEquals(DownloadStatus.DOWNLOADED,b.row?.status)
                assertFalse(nodes(scene).any {it.config.getOrNull(SemanticsProperties.Text)?.any {text ->text.text==s[UiText.DL_HTTP_ERROR]}==true})
            } finally {scene.close();manager.close();runCurrent();manager.awaitClosed()}
        }
    }
    @Test fun deniedRightsAndUnsupportedFormatsExplainWhyWithoutStartingTransfer()=runTest {
        for(failure in listOf(DownloadFailure.RIGHTS_UNVERIFIED,DownloadFailure.UNSUPPORTED_FORMAT)) {
            val b=Backend().also {it.offerFailure=failure};val s=LocalizedStrings()
            val manager=OfflineDownloads(b,StandardTestDispatcher(testScheduler));val scene=ImageComposeScene(320,440,Density(1f,1.8f),coroutineContext=StandardTestDispatcher(testScheduler))
            try {
                scene.setContent {CompositionLocalProvider(LocalAppStrings provides s) {ApplicationTheme(ApplicationThemeMode.LIGHT) {DownloadAction(b.p,manager)}}}
                draw(scene);click(scene,s[UiText.DL_DOWNLOAD])
                assertTrue(nodes(scene).any {has(it,s[failure.label()])});assertEquals(0,b.opens)
                val close=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && has(it,s[UiText.CLOSE])}
                assertTrue(close.boundsInRoot.top>=0 && close.boundsInRoot.bottom<=440)
                assertTrue(nodes(scene).any {it.config.contains(SemanticsActions.ScrollBy)})
                click(scene,s[UiText.CLOSE]);assertTrue(manager.catalogue.value.entries.isEmpty())
            } finally {scene.close();manager.close();runCurrent();manager.awaitClosed()}
        }
    }
    @Test fun consentStartsOnlyOneTransferAndCompletionAndDeletionRemainIndependentOfDialogLifetime()=runTest {
        val b=Backend();val manager=OfflineDownloads(b,StandardTestDispatcher(testScheduler));val strings=LocalizedStrings()
        val scene=ImageComposeScene(360,720,coroutineContext=StandardTestDispatcher(testScheduler));var visible by mutableStateOf(true)
        try {
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings) {ApplicationTheme(ApplicationThemeMode.DARK) {if(visible) DownloadAction(b.p,manager)}}}
            draw(scene);click(scene,strings[UiText.DL_DOWNLOAD]);assertEquals(0,b.opens)
            assertTrue(nodes(scene).any {has(it,strings[UiText.DL_CONSENT])})
            click(scene,strings[UiText.DL_DOWNLOAD]);assertEquals(1,b.opens)
            visible=false;draw(scene);assertEquals(DownloadStatus.DOWNLOADING,b.row?.status)
            b.gate.complete(Unit);draw(scene);visible=true;draw(scene)
            assertEquals(setOf(b.id),manager.available.value)
            val ready=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && has(it,strings[UiText.DL_AVAILABLE])};assertTrue(ready.size.width>=48 && ready.size.height>=48)
            click(scene,strings[UiText.DL_AVAILABLE]);assertEquals(0,b.removed);click(scene,strings[UiText.CANCEL]);assertEquals(0,b.removed)
            click(scene,strings[UiText.DL_AVAILABLE]);click(scene,strings[UiText.DL_DELETE]);assertEquals(1,b.removed);assertTrue(manager.available.value.isEmpty())
        } finally {scene.close();manager.close();runCurrent();manager.awaitClosed()}
    }
    @Test fun cancelDuringNavigationDoesNotLeaveAnAvailablePartialCopy()=runTest {
        val b=Backend();val manager=OfflineDownloads(b,StandardTestDispatcher(testScheduler));val s=LocalizedStrings();val scene=ImageComposeScene(320,540,coroutineContext=StandardTestDispatcher(testScheduler))
        try {
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides s) {ApplicationTheme(ApplicationThemeMode.LIGHT) {DownloadAction(b.p,manager)}}}
            draw(scene);click(scene,s[UiText.DL_DOWNLOAD]);click(scene,s[UiText.DL_DOWNLOAD]);assertEquals(1,b.opens)
            click(scene,s[UiText.DL_CANCEL]);assertEquals(DownloadStatus.CANCELLED,b.row?.status);assertTrue(manager.available.value.isEmpty())
        } finally {scene.close();manager.close();runCurrent();manager.awaitClosed()}
    }
    @Test fun localizedNarrowLargeFontManagerKeepsCloseVisibleAndContentScrollable()=runTest {
        deviceInterfaceLanguage();val previous=Locale.getDefault()
        val tables=try {interfaceLanguages.associateWith {language -> applyInterfaceLanguage(language);loadInterfaceStrings(getSystemResourceEnvironment())}} finally {Locale.setDefault(previous)}
        for((language,strings) in tables) for(dark in listOf(false,true)) for(font in listOf(1f,1.8f)) {
            val b=Backend();b.row=DownloadEntry(b.p,b.r.format,DownloadStatus.DOWNLOADED,b.r,1000,revision=1);b.revision=1
            val manager=OfflineDownloads(b,StandardTestDispatcher(testScheduler));val scene=ImageComposeScene(320,440,Density(1f,font),coroutineContext=StandardTestDispatcher(testScheduler))
            try {
                scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings) {ApplicationTheme(if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT) {DownloadPanel(manager,{},{})}}}
                draw(scene)
                val close=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && has(it,strings[UiText.CLOSE])}
                assertTrue(close.boundsInRoot.top>=0 && close.boundsInRoot.bottom<=440,"$language / $font / close");assertTrue(close.size.height>=48)
                assertTrue(nodes(scene).any {it.config.contains(SemanticsActions.ScrollBy)},"$language / $font / scroll")
                assertEquals(0,b.opens)
            } finally {scene.close();manager.close();runCurrent();manager.awaitClosed()}
        }
    }
}
