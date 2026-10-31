// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app

import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.infinilect.app.discovery.*
import org.infinilect.app.downloads.*
import org.infinilect.app.ui.*
import org.infinilect.core.*
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.*

/** Actual shared dialogs/controls at narrow, large-font sizes. Not a physical Android test. */
@OptIn(ExperimentalComposeUiApi::class,ExperimentalCoroutinesApi::class)
class CatalogPublicationUiTest {
    private val id=PublicationId(SourceId("catalog-fixture"),"101")
    private val book=Publication(id,"Original metadata 世界",PublicationType.DOCUMENT,sourceUrl="https://es.wikisource.org/wiki/Original")
    private fun table(language:String):LocalizedStrings {
        val xml=DocumentBuilderFactory.newInstance().apply {setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)}.newDocumentBuilder()
            .parse(Path.of("src/commonMain/composeResources",if(language=="en")"values" else "values-$language","strings.xml").toFile()).getElementsByTagName("string")
        val values=(0 until xml.length).associate {i->val e=xml.item(i) as org.w3c.dom.Element;e.getAttribute("name") to e.textContent}
        return LocalizedStrings(UiText.entries.associateWith {values.getValue(it.name.lowercase())},localeTag=language)
    }
    private fun nodes(scene:ImageComposeScene):List<SemanticsNode> {
        fun walk(node:SemanticsNode):List<SemanticsNode> = listOf(node)+node.children.flatMap(::walk)
        return scene.semanticsOwners.flatMap {walk(it.unmergedRootSemanticsNode)}
    }
    private fun contains(node:SemanticsNode,label:String):Boolean =
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any {it.text==label} || node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) || node.children.any {contains(it,label)}
    private fun action(scene:ImageComposeScene,label:String)=nodes(scene).last {it.config.contains(SemanticsActions.OnClick) && contains(it,label)}
    private fun text(scene:ImageComposeScene)=nodes(scene).flatMap {it.config.getOrNull(SemanticsProperties.Text).orEmpty()}.map {it.text}

    @Test fun pendingMetadataAndExplicitBrowserActionReactToThreeLanguagesWithoutRefetching()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val gate=CompletableDeferred<Unit>();var calls=0;var reads=0
        val source=object:CatalogPublicationSource {override suspend fun catalogInfo(id:PublicationId):CatalogPublicationInfo {
            calls++;gate.await();return CatalogPublicationInfo(CatalogPageKind.UNKNOWN,CatalogEpubState.POTENTIAL,officialUrl=book.sourceUrl,authorized=true,metadata=book.copy(authors=listOf("Original resolved creator")))
        }}
        val tables=listOf("en","es","ca").associateWith(::table);var language by mutableStateOf("en");var dark by mutableStateOf(false)
        val opened=mutableListOf<String>();val browser=object:UriHandler {override fun openUri(uri:String){opened+=uri}}
        val scene=ImageComposeScene(320,720,Density(1f,1.8f),coroutineContext=dispatcher)
        var frame=0L;fun draw(){repeat(8){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides tables.getValue(language),LocalUriHandler provides browser) {
                ApplicationTheme(if(dark) ApplicationThemeMode.DARK else ApplicationThemeMode.LIGHT) {
                    PublicationDetails(book,"Wikisource (es)",close={},catalog=source) {PublicationReadAction(null){reads++}}
                }
            }}
            draw();assertTrue(action(scene,tables.getValue("en")[UiText.UI_START_READING]).config.contains(SemanticsProperties.Disabled))
            assertTrue(tables.getValue("en")[UiText.WIKI_CATALOG_CHECKING] in text(scene));assertEquals(1,calls);assertTrue(opened.isEmpty())
            gate.complete(Unit);draw()
            for(code in listOf("en","es","ca")) {
                language=code;dark=!dark;draw();val strings=tables.getValue(code)
                assertTrue(strings[UiText.WIKI_KIND_UNKNOWN] in text(scene));assertFalse(strings[UiText.WIKI_KIND_WORK] in text(scene))
                assertTrue(strings[UiText.WIKI_EPUB_POTENTIAL] in text(scene));assertTrue("Original resolved creator" in text(scene))
                assertFalse(action(scene,strings[UiText.UI_START_READING]).config.contains(SemanticsProperties.Disabled))
                val official=action(scene,strings[UiText.WIKI_OFFICIAL_PAGE]);assertTrue(official.boundsInRoot.height>=48f)
                assertTrue(opened.size==if(code=="en")0 else if(code=="es")1 else 2)
                assertTrue(official.config[SemanticsActions.OnClick].action!!.invoke())
                assertEquals(book.sourceUrl,opened.last());assertEquals(1,calls)
            }
            assertEquals(0,reads)
        }finally{gate.complete(Unit);scene.close();runCurrent()}
    }

    @Test fun lateNonCooperativeMetadataCannotEnableActionsForAnotherPage()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val gate=CompletableDeferred<Unit>();var current by mutableStateOf(book)
        val second=book.copy(id=PublicationId(id.sourceId,"202"),title="Other original metadata")
        val source=object:CatalogPublicationSource {override suspend fun catalogInfo(id:PublicationId):CatalogPublicationInfo {
            if(id.localId=="101")withContext(NonCancellable){gate.await()}
            return if(id.localId=="101")CatalogPublicationInfo(CatalogPageKind.WORK,CatalogEpubState.POTENTIAL,authorized=true)
                else CatalogPublicationInfo(CatalogPageKind.AUTHOR,CatalogEpubState.WEB_ONLY)
        }}
        val strings=table("ca");val scene=ImageComposeScene(360,640,Density(1f,1.5f),coroutineContext=dispatcher)
        var frame=0L;fun draw(){repeat(8){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings) {ApplicationTheme(ApplicationThemeMode.DARK) {
                PublicationDetails(current,"Wikisource (ca)",close={},catalog=source) {PublicationReadAction(null){error("Catalog-only page is not readable")}}
            }}}
            draw();current=second;draw()
            assertTrue(strings[UiText.WIKI_KIND_AUTHOR] in text(scene))
            assertTrue(action(scene,strings[UiText.UI_START_READING]).config.contains(SemanticsProperties.Disabled))
            gate.complete(Unit);draw()
            assertTrue(strings[UiText.WIKI_KIND_AUTHOR] in text(scene));assertFalse(strings[UiText.WIKI_KIND_WORK] in text(scene))
            assertTrue(action(scene,strings[UiText.UI_START_READING]).config.contains(SemanticsProperties.Disabled))
        }finally{gate.complete(Unit);scene.close();runCurrent()}
    }

    private class RetainedBackend(val publication:Publication):DownloadBackend {
        override fun capability(id:PublicationId)=DownloadCapability.AUTHORIZED_SOURCE
        override suspend fun load()=snapshot()
        override suspend fun snapshot()=DownloadSnapshot(listOf(DownloadEntry(publication,PublicationFormat.EPUB,DownloadStatus.DOWNLOADED,publication.resources.single())))
        override suspend fun offers(id:PublicationId):List<DownloadOffer> = error("Metadata failure cannot start a new download")
        override suspend fun pending(offer:DownloadOffer):DownloadEntry=error("No writes")
        override suspend fun download(entry:DownloadEntry,started:suspend(DownloadEntry)->Unit,progress:suspend(DownloadProgress)->Unit):DownloadEntry=error("No transfer")
        override suspend fun settle(id:PublicationId,status:DownloadStatus,failure:DownloadFailure?):DownloadEntry?=null
        override suspend fun remove(id:PublicationId)=Unit
        override suspend fun acquire(id:PublicationId):OfflineReadingCopy?=null
        override suspend fun quota(bytes:Long)=Unit
        override suspend fun close()=Unit
    }
    @Test fun metadataFailureKeepsRetainedReadingAndDeletionAvailableWithoutExposingErrors()=runTest {
        val dispatcher=StandardTestDispatcher(testScheduler);val publication=book.copy(resources=listOf(PublicationResource(id,"original",PublicationFormat.EPUB,"application/epub+zip")))
        val manager=OfflineDownloads(RetainedBackend(publication),dispatcher)
        val source=object:CatalogPublicationSource {override suspend fun catalogInfo(id:PublicationId):CatalogPublicationInfo=error("private/path and credential must not display")}
        val strings=table("es");val scene=ImageComposeScene(320,720,Density(1f,1.5f),coroutineContext=dispatcher)
        var frame=0L;fun draw(){repeat(8){runCurrent();scene.render(++frame*16_666_667L).close()};runCurrent()}
        try {
            scene.setContent {CompositionLocalProvider(LocalAppStrings provides strings) {ApplicationTheme(ApplicationThemeMode.LIGHT) {
                PublicationDetails(book,"Wikisource (es)",close={},catalog=source,downloads=manager) {PublicationReadAction(null){}}
            }}}
            draw();assertTrue(strings[UiText.WIKI_CATALOG_ERROR] in text(scene));assertFalse(text(scene).any {it.contains("credential")})
            assertFalse(action(scene,strings[UiText.UI_START_READING]).config.contains(SemanticsProperties.Disabled))
            val retained=action(scene,strings[UiText.DL_AVAILABLE]);assertFalse(retained.config.contains(SemanticsProperties.Disabled))
            assertTrue(retained.config[SemanticsActions.OnClick].action!!.invoke());draw()
            assertTrue(strings[UiText.DL_DELETE] in text(scene));assertTrue(strings[UiText.DL_DELETE_NOTICE] in text(scene))
        }finally{scene.close();manager.close();runCurrent();manager.awaitClosed()}
    }
}
