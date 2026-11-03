// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.infinilect.app.discovery.*
import org.infinilect.core.PublicationId

/** Dialog-local presentation only; source/reader/download owners remain authoritative. */
internal data class CatalogActionPolicy(val mayRead:Boolean=true,val mayDownload:Boolean=true)
internal val LocalCatalogActions=staticCompositionLocalOf {CatalogActionPolicy()}
internal data class CatalogPresentation(val info:CatalogPublicationInfo?=null,val checking:Boolean=false,val failed:Boolean=false,val verifying:Boolean=false)

@Composable
internal fun catalogPresentation(source:CatalogPublicationSource?,id:PublicationId,retry:Int,verify:Boolean=false):State<CatalogPresentation> = key(source,id,retry) {
    produceState(CatalogPresentation(checking=source!=null,verifying=verify),source,id,retry) {
        if(source!=null) {
            try {
                val info=if(verify)source.checkEpub(id) else if(retry>0)source.refreshCatalogInfo(id) else source.catalogInfo(id)
                currentCoroutineContext().ensureActive()
                value=CatalogPresentation(info=info)
            }catch(e:CancellationException){throw e}
            catch(_:Exception){currentCoroutineContext().ensureActive();value=CatalogPresentation(failed=true)}
        }
    }
}

internal fun catalogActionPolicy(sourcePresent:Boolean,presentation:CatalogPresentation,retained:Boolean)=CatalogActionPolicy(
    mayRead=!sourcePresent || retained || presentation.info?.mayRead==true,
    mayDownload=!sourcePresent || presentation.info?.mayDownload==true,
)

internal fun CatalogPageKind.label()=when(this) {
    CatalogPageKind.TEXT->UiText.WIKI_KIND_TEXT
    CatalogPageKind.WORK->UiText.WIKI_KIND_WORK
    CatalogPageKind.CHAPTER->UiText.WIKI_KIND_CHAPTER
    CatalogPageKind.AUTHOR->UiText.WIKI_KIND_AUTHOR
    CatalogPageKind.INDEX->UiText.WIKI_KIND_INDEX
    CatalogPageKind.VERSIONS->UiText.WIKI_KIND_VERSIONS
    CatalogPageKind.DISAMBIGUATION->UiText.WIKI_KIND_DISAMBIGUATION
    CatalogPageKind.UNKNOWN->UiText.WIKI_KIND_UNKNOWN
}
internal fun CatalogEpubState.label()=when(this) {
    CatalogEpubState.POTENTIAL->UiText.WIKI_EPUB_POTENTIAL
    CatalogEpubState.VALIDATED->UiText.WIKI_EPUB_VALIDATED
    CatalogEpubState.WEB_ONLY->UiText.WIKI_EPUB_WEB_ONLY
    CatalogEpubState.UNAVAILABLE->UiText.WIKI_EPUB_UNAVAILABLE
    CatalogEpubState.INCOMPATIBLE->UiText.WIKI_EPUB_INCOMPATIBLE
    CatalogEpubState.EXPORTABLE->UiText.WIKI_EPUB_EXPORTABLE
    CatalogEpubState.TEMPORARY_ERROR->UiText.WIKI_EPUB_TEMPORARY
}

internal fun CatalogRightsState.label()=when(this) {
    CatalogRightsState.VERIFIED->UiText.WIKI_RIGHTS_VERIFIED
    CatalogRightsState.INSUFFICIENT->UiText.WIKI_EPUB_RIGHTS_UNVERIFIED
    CatalogRightsState.UNKNOWN->UiText.WIKI_RIGHTS_UNKNOWN
    CatalogRightsState.RESTRICTED->UiText.WIKI_RIGHTS_RESTRICTED
    CatalogRightsState.PENDING->UiText.WIKI_RIGHTS_PENDING
}

/** Defense in depth for an adapter-validated page URL. No URI is resolved/fetched.
 * This action opens the system browser only after an explicit click. */
internal fun safeCatalogOfficialPage(value:String):Boolean {
    if(value.length !in 1..2048 || !value.startsWith("https://") || value.any {it.isWhitespace() || it.isISOControl() || it=='\\'})return false
    val authority=value.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    if(authority.length !in 1..253 || ':' in authority || '@' in authority)return false
    val labels=authority.split('.')
    return labels.size>=2 && labels.all {it.length in 1..63 && Regex("[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?").matches(it)} &&
        Regex("[a-zA-Z]{2,63}").matches(labels.last()) && labels.last().lowercase() !in setOf("local","localhost","internal","lan","home","test","invalid","onion")
}

@Composable
internal fun CatalogPublicationStatus(presentation:CatalogPresentation,openOfficialPage:(String)->Unit,checkEpub:(()->Unit)?=null,retry:()->Unit) {
    val strings=LocalAppStrings.current
    var openFailed by remember(presentation.info?.officialUrl) {mutableStateOf(false)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(presentation.checking) Text(strings[if(presentation.verifying)UiText.WIKI_CHECKING_EPUB else UiText.WIKI_CATALOG_CHECKING],style=MaterialTheme.typography.caption,
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        if(presentation.failed) {
            Text(strings[UiText.WIKI_CATALOG_ERROR],style=MaterialTheme.typography.body2,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
            TextButton(retry,Modifier.heightIn(min=48.dp)) {Text(strings[UiText.RETRY])}
        }
        presentation.info?.let {info ->
            Text(strings[info.kind.label()],style=MaterialTheme.typography.subtitle2)
            Text(strings[info.epubState.label()],style=MaterialTheme.typography.body2)
            if(info.epubState!=CatalogEpubState.WEB_ONLY) {
                Text(strings[info.rightsState.label()],style=MaterialTheme.typography.body2)
                if(checkEpub!=null) {
                    TextButton(if(info.authorized)checkEpub else retry,Modifier.heightIn(min=48.dp)) {Text(strings[if(info.authorized)UiText.WIKI_CHECK_EPUB else UiText.WIKI_RECHECK_RIGHTS])}
                    if(info.authorized)Text(strings[UiText.WIKI_CHECK_CONSENT],style=MaterialTheme.typography.caption)
                }
            }
            info.officialUrl?.takeIf(::safeCatalogOfficialPage)?.let {url ->
                TextButton({try {openOfficialPage(url);openFailed=false}catch(_:Exception){openFailed=true}},Modifier.heightIn(min=48.dp)) {Text(strings[UiText.WIKI_OFFICIAL_PAGE])}
            }
        }
        if(openFailed) Text(strings[UiText.WIKI_OFFICIAL_OPEN_FAILED],style=MaterialTheme.typography.caption,color=MaterialTheme.colors.error,
            modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    }
}
