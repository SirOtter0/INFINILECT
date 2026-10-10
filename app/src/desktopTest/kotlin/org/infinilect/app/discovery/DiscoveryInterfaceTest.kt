// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.discovery

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.*
import org.infinilect.app.ui.*
import org.infinilect.app.collections.*
import org.infinilect.core.*
import kotlin.test.*
import java.nio.file.*

/** Synthetic original catalog metadata in real shared Compose, never live provider artwork. */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class DiscoveryInterfaceTest {
    private class Fixture(val test: TestScope, width: Int, height: Int, dark: Boolean=false, fontScale: Float=1f) : AutoCloseable {
        val source=CatalogFixture(SourceId("internet-archive"));val gutenberg=CatalogFixture(SourceId("gutenberg"))
        val data=FakeCollections()
        val appearance=ApplicationAppearancePreferences(object: ApplicationAppearanceStore {
            override suspend fun load()=if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT
            override suspend fun save(mode: ApplicationThemeMode)=true
            override suspend fun loadPreferences()=ApplicationPreferences(if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT,LocalProfile("Alex","ES",null,true))
        },StandardTestDispatcher(test.testScheduler))
        val owner=ApplicationSources(listOf(SourceOption("Internet Archive",source),SourceOption("Project Gutenberg (experimental)",gutenberg)),
            collections=ApplicationCollections(data.library,data.history,StandardTestDispatcher(test.testScheduler)),appearance=appearance,
            sessionDispatcher=StandardTestDispatcher(test.testScheduler)) {}
        val app=owner.applicationSession()
        val scene=ImageComposeScene(width,height,Density(1f,fontScale))
        var back: (() -> Unit)?=null
        init {
            source.respond={request,token->
                val first=token?.toIntOrNull()?:0
                DiscoveryPage(List(10){index->source.entry("${first+index}",listOf(request.genre?.archiveSubject?:"Philosophy")).let { it.copy(publication=it.publication.copy(title="Original reading ${first+index}",authors=listOf("Fixture author"))) }},"${first+10}")
            }
            gutenberg.respond={_,_->DiscoveryPage(listOf(gutenberg.entry("1",listOf("Fantasy")).let {it.copy(publication=it.publication.copy(title="An original tale"))}))}
            scene.setContent { App(owner,backHandler={enabled,callback->SideEffect {back=if(enabled)callback else null}}) }
            pump()
        }
        fun pump() {repeat(12){test.runCurrent();scene.render(test.testScheduler.currentTime*1_000_000).close()};test.runCurrent()}
        fun nodes():List<SemanticsNode> {
            fun walk(n:SemanticsNode):List<SemanticsNode> = listOf(n)+n.children.flatMap(::walk)
            return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
        }
        fun has(n:SemanticsNode,label:String)=n.config.getOrNull(SemanticsProperties.Text)?.any{it.text==label}==true||n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label)==true
        fun visible(label:String)=nodes().any{has(it,label)}
        fun click(label:String) {
            fun matches(n:SemanticsNode):Boolean=has(n,label)||n.children.any(::matches)
            val control=assertNotNull(nodes().lastOrNull{!it.config.contains(SemanticsProperties.Disabled)&&it.config.getOrNull(SemanticsActions.OnClick)?.action!=null&&matches(it)},label)
            assertTrue(control.config[SemanticsActions.OnClick].action!!.invoke());pump()
        }
        fun search() {app.navigate(Destination.SEARCH);app.discovery.edit("books");test.advanceTimeBy(350);pump()}
        fun capture(name:String) {
            System.getenv("INFINILECT_UI_PREVIEW_DIRECTORY")?.let { path ->
                val dir=Path.of(path);Files.createDirectories(dir)
                scene.render(test.testScheduler.currentTime*1_000_000).use {image->Files.write(dir.resolve(name),assertNotNull(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)).use {it.bytes})}
            }
        }
        override fun close(){scene.close();owner.close();test.runCurrent()}
    }
    @Test fun compactAndWideSearchShowsAttributionCoversFiltersAndAccessibleDetails()=runTest {
        for(width in listOf(390,1280)) Fixture(this,width,900).use { f ->
            f.search();assertTrue(f.visible("Original reading 0"));assertTrue(f.visible("Internet Archive"))
            val grid=f.nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null }
            assertTrue(grid.config[SemanticsActions.ScrollToIndex].action!!.invoke(11));f.pump();assertTrue(f.visible("Project Gutenberg (experimental)"))
            assertTrue(grid.config[SemanticsActions.ScrollToIndex].action!!.invoke(1));f.pump()
            f.click("Sources & filters");assertTrue(f.visible("All languages"));assertTrue(f.visible("English"))
            f.click("Sources & filters");f.click("Details for Original reading 0")
            assertTrue(f.visible("Publication details"));assertTrue(f.visible("Catalog metadata only. Acquisition is unavailable for this source."))
            assertTrue(f.visible("Categories"));f.click("Close");assertEquals(Destination.SEARCH,f.app.destination.value)
            assertEquals(0,f.source.acquisition+f.gutenberg.acquisition)
        }
    }
    @Test fun controlsDoNotRequeryAndLeavingSearchCancelsPendingRequests()=runTest {
        Fixture(this,390,800).use { f ->
            f.search();val calls=f.source.calls+f.gutenberg.calls
            repeat(3){f.click("Sources & filters")};assertEquals(calls,f.source.calls+f.gutenberg.calls)
            val gate=CompletableDeferred<DiscoveryPage>();f.source.respond={_,_->gate.await()}
            f.app.discovery.edit("pending");advanceTimeBy(350);f.pump();assertTrue(f.app.discovery.state.value.loading)
            f.app.navigate(Destination.LIBRARY);f.pump();assertFalse(f.app.discovery.state.value.loading)
            gate.complete(DiscoveryPage(listOf(f.source.entry("obsolete"))));f.pump()
            assertFalse(f.app.discovery.state.value.entries.any{it.publication.id.localId=="obsolete"})
        }
    }
    @Test fun homeOfflineLocalGreetingAndOptInRemainIndependentOfRemoteFailure()=runTest {
        Fixture(this,390,900).use {f->
            assertTrue(f.visible("Hello, Alex!"));assertTrue(f.visible("Your next read starts here"));assertEquals(0,f.source.calls)
            f.source.respond={_,_->error("offline")};f.gutenberg.respond={_,_->error("offline")}
            f.click("Explore online catalogs");assertTrue(f.visible("Hello, Alex!"));assertTrue(f.visible("Your next read starts here"));assertTrue(f.visible("Online discovery unavailable"))
        }
    }
    @Test fun genreViewAllUsesActualFilterAndResultsDoNotWriteHistory()=runTest {
        Fixture(this,390,900).use { f ->
            f.appearance.changeDiscovery(DiscoveryPreferences(homeEnabled=true,interests=setOf(Genre.PHILOSOPHY)));f.pump()
            assertTrue(f.app.homeDiscovery.state.value.rows.any{it.genre==Genre.PHILOSOPHY})
            f.app.discovery.browse(Genre.PHILOSOPHY);f.app.navigate(Destination.SEARCH);f.pump()
            assertEquals(Genre.PHILOSOPHY,f.app.discovery.state.value.genre);assertTrue(f.data.opened.isEmpty());assertTrue(f.data.saved.isEmpty())
            f.click("Details for Original reading 0");assertTrue(f.data.opened.isEmpty());f.click("Close")
        }
    }
    @Test fun keyboardNavigationAndBackPreserveQueryAndCatalogIdentity()=runTest {
        Fixture(this,1280,850).use { f ->
            f.search();val entries=f.app.discovery.state.value.entries
            f.scene.sendKeyEvent(KeyEvent(Key.Five,KeyEventType.KeyDown,isAltPressed=true));f.pump()
            // Application navigation remains accessible independently of catalog result focus.
            f.click("Navigate to Settings");f.click("Navigate to Search")
            assertEquals("books",f.app.discovery.state.value.query);assertEquals(entries,f.app.discovery.state.value.entries)
            assertNotNull(f.back).invoke();f.pump();assertEquals(Destination.HOME,f.app.destination.value)
        }
    }
    @Test fun narrowLargeFontLayoutRetainsScrollableResultsAndFilterAccessibility()=runTest {
        Fixture(this,320,420,fontScale=1.5f).use { f ->
            f.search();assertTrue(f.nodes().any{it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null})
            f.click("Sources & filters");assertTrue(f.visible("English"))
            val resultScroll=f.nodes().first{it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null}
            assertTrue(resultScroll.boundsInRoot.height>0)
        }
    }
    @Test fun androidSizedAndDesktopPreviewsUseSharedLightDarkUiAndSyntheticMetadata()=runTest {
        for ((width,dark) in listOf(390 to false,390 to true,1280 to false)) Fixture(this,width,900,dark).use {f->
            val suffix=if(width==390) "android${if(dark) "-dark" else ""}" else "desktop"
            f.search();f.capture("discovery-search-$suffix.png")
            f.app.navigate(Destination.HOME);f.appearance.changeDiscovery(DiscoveryPreferences(homeEnabled=true,interests=setOf(Genre.PHILOSOPHY)));f.pump();f.capture("discovery-home-$suffix.png")
        }
    }

    @Test fun remountingSearchKeepsItsScrolledPassageAndDoesNotRefetchCompletedResults()=runTest {
        Fixture(this,390,900).use { f ->
            f.search()
            fun grid()=f.nodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex)!=null && it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)!=null }
            assertTrue(grid().config[SemanticsActions.ScrollToIndex].action!!.invoke(8)); f.pump()
            val before=grid().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(before>0)
            val calls=f.source.calls+f.gutenberg.calls
            f.app.navigate(Destination.HOME); f.pump(); f.app.navigate(Destination.SEARCH); f.pump()
            assertEquals(before,grid().config[SemanticsProperties.VerticalScrollAxisRange].value())
            assertEquals(calls,f.source.calls+f.gutenberg.calls)
        }
    }

    @Test fun clearSearchCancelsPendingResultsPreservesFiltersAndHasAccessibleAction()=runTest {
        Fixture(this,390,900).use { f ->
            f.search(); f.app.discovery.language("en"); f.pump()
            val gate=CompletableDeferred<DiscoveryPage>()
            f.source.respond={_,_->gate.await()}
            f.app.discovery.edit("pending"); advanceTimeBy(350); f.pump()
            f.click("Clear search")
            assertEquals("",f.app.discovery.state.value.query)
            assertEquals("en",f.app.discovery.state.value.language)
            assertTrue(f.app.discovery.state.value.entries.isEmpty())
            gate.complete(DiscoveryPage(listOf(f.source.entry("obsolete")))); advanceTimeBy(350); f.pump()
            assertFalse(f.visible("Clear search")); assertFalse(f.app.discovery.state.value.loading)
            assertTrue(f.app.discovery.state.value.entries.isEmpty())
        }
    }

    @Test fun repeatedHomeSearchDetailsAndDiscoveryChangesHaveStableLazyIdentities()=runTest {
        Fixture(this,390,900).use { f ->
            f.appearance.changeDiscovery(DiscoveryPreferences(homeEnabled=true)); f.pump()
            repeat(30) { index ->
                f.app.navigate(Destination.SEARCH); f.app.discovery.edit("Cervantes $index"); advanceTimeBy(350); f.pump()
                f.click("Details for Original reading 0"); f.click("Close")
                f.app.navigate(Destination.HOME); f.pump()
                f.appearance.changeDiscovery(DiscoveryPreferences(homeEnabled=index%2==0)); f.pump()
            }
            assertTrue(f.data.opened.isEmpty()); assertTrue(f.data.saved.isEmpty())
            assertTrue(f.visible("Hello, Alex!"))
        }
    }

    @Test fun enterActivatesFocusedClearButtonRatherThanSubmittingTheEnclosingSearchField()=runTest {
        Fixture(this,1280,900).use { f ->
            f.search()
            val button=f.nodes().first { f.has(it,"Clear search") && it.config.getOrNull(SemanticsActions.RequestFocus)?.action!=null }
            assertTrue(button.config[SemanticsActions.RequestFocus].action!!.invoke()); f.pump()
            f.scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyUp));f.pump()
            assertEquals("",f.app.discovery.state.value.query)
        }
    }

    @Test fun enterSubmitsFocusedQueryImmediatelyWithoutWaitingForTheTypingDebounce()=runTest {
        Fixture(this,1280,900).use { f ->
            f.search()
            val field=f.nodes().first {it.config.getOrNull(SemanticsActions.SetText)?.action!=null}
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("Cervantes")))
            assertTrue(field.config[SemanticsActions.RequestFocus].action!!.invoke()); f.pump()
            assertNull(f.app.discovery.state.value.request)
            val time=testScheduler.currentTime
            f.scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyDown));f.scene.sendKeyEvent(KeyEvent(Key.Enter,KeyEventType.KeyUp));f.pump()
            assertEquals(time,testScheduler.currentTime)
            assertEquals("Cervantes",f.app.discovery.state.value.request?.query)
            assertTrue(f.app.discovery.state.value.entries.isNotEmpty())
        }
    }
    @Test fun classifiedGutenbergFailureIsAccessibleWhileArchiveResultsAndRetryRemainUsable()=runTest {
        Fixture(this,390,900).use {f->
            f.gutenberg.respond={_,_->throw CatalogSourceException(CatalogErrorKind.TLS)}
            f.search()
            val message=catalogFailureMessage("Project Gutenberg (experimental)",CatalogErrorKind.TLS)
            assertTrue(f.visible(message))
            assertTrue(f.app.discovery.state.value.entries.all {it.publication.id.sourceId==f.source.id})
            f.gutenberg.respond={_,_->DiscoveryPage(listOf(f.gutenberg.entry("recovered")))}
            f.click(DiscoveryStrings.retrySource("Project Gutenberg (experimental)"))
            assertTrue(f.app.discovery.state.value.catalogs.none {it.failed})
            assertTrue(f.app.discovery.state.value.entries.any {it.publication.id.sourceId==f.gutenberg.id})
        }
    }
}
