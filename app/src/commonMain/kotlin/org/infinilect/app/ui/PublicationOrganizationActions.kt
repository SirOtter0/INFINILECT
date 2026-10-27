// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.infinilect.app.collections.CollectionsController
import org.infinilect.app.covers.PublicationCovers
import org.infinilect.core.*

internal const val ORGANIZATION_ANIMATION_MS = 220
internal fun favoriteActionLabel(active: Boolean) = if(active) UiText.LIB_REMOVE_FAVORITE else UiText.LIB_ADD_FAVORITE

/** State is always supplied by the owner: persisted in details, pending in the atomic form.
 * Compose animation clocks honor MotionDurationScale (including zero). No perpetual animation.
 * Scale is applied to drawing only; the 48dp touch/focus target never moves or shrinks. */
@Composable
internal fun OrganizationToggle(label: String, glyph: LibraryGlyph, active: Boolean, enabled: Boolean = true, click: () -> Unit) {
    val target = if(active) {
        if(glyph==LibraryGlyph.HEART) if(MaterialTheme.colors.isLight) Color(0xffb93848) else Color(0xffff8a91)
        else MaterialTheme.colors.primary
    } else MaterialTheme.colors.onSurface
    val color by animateColorAsState(target,tween(ORGANIZATION_ANIMATION_MS))
    val fill by animateFloatAsState(if(active) 1f else 0f,tween(ORGANIZATION_ANIMATION_MS))
    val scale=remember {Animatable(1f)}
    var previous by remember {mutableStateOf(active)}
    LaunchedEffect(active) {
        if(previous!=active) {
            previous=active
            scale.animateTo(1f,keyframes {
                durationMillis=ORGANIZATION_ANIMATION_MS
                (if(active) 1.15f else .96f) at 70 using FastOutSlowInEasing
                1f at ORGANIZATION_ANIMATION_MS using FastOutSlowInEasing
            })
        }
    }
    val strings=LocalAppStrings.current
    LibraryIconButton(label,glyph,enabled=enabled,modifier=Modifier.semantics {
        selected=active;stateDescription=strings[if(active) UiText.UI_SELECTED else UiText.UI_NOT_SELECTED]
    },icon={
        LibraryIcon(glyph,active,Modifier.graphicsLayer {scaleX=scale.value;scaleY=scale.value},color.copy(alpha=if(enabled) 1f else .38f),fill)
    },click=click)
}

/** One shared metadata action row for local, catalog and discovered publications.
 * Controllers own writes independently of the dialog. The observer coroutine is dialog-owned;
 * leaving stops feedback, not the durable operation. No optimistic membership/favorite copy. */
@Composable
internal fun PublicationOrganizationActions(publication: Publication, controller: CollectionsController,
    covers: PublicationCovers?, feedback: SnackbarHostState) {
    val strings=LocalAppStrings.current
    val membership by controller.membership.collectAsState()
    val library by controller.library.collectAsState()
    val tags by controller.tags.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.error.collectAsState()
    val organizationResult by controller.organizationResult.collectAsState()
    val scope=rememberCoroutineScope()
    var feedbackJob by remember {mutableStateOf<Job?>(null)}
    var confirm by remember(publication.id) {mutableStateOf(false)}
    var organize by remember(publication.id) {mutableStateOf(false)}
    val entry=library.entries.firstOrNull {it.publication.id==publication.id}
    val action=membership.forPublication(publication.id)
    val enabled=action.enabled && !busy
    LaunchedEffect(action.inLibrary) {if(action.inLibrary==false) {organize=false;confirm=false}}
    LaunchedEffect(organizationResult,strings.localeTag) {
        if(organizationResult==true) {
            feedback.showSnackbar(strings[UiText.LIB_SAVED],duration=SnackbarDuration.Short)
            controller.dismissOrganizationSuccess()
        }
    }
    fun changeMembership(remove: Boolean) {
        // Fresh authoritative guard handles a second tap before recomposition.
        val current=controller.membership.value.forPublication(publication.id)
        if(!current.enabled || controller.busy.value || current.inLibrary!=remove) return
        val job=(if(remove) controller.removeLibrary(publication.id) else controller.toggleCatalogLibrary(publication)) ?: return
        feedbackJob?.cancel() // Latest feedback only; never cancel the controller-owned write.
        feedbackJob=scope.launch {
            job.join();ensureActive()
            if(!job.isCancelled && controller.error.value==null && controller.membership.value.forPublication(publication.id).inLibrary==!remove)
                feedback.showSnackbar(strings[if(remove) UiText.LIB_REMOVED_NOTICE else UiText.LIB_ADDED_NOTICE],duration=SnackbarDuration.Short)
        }
    }
    Row(Modifier.fillMaxWidth()) {
        OrganizationToggle(if(action.inLibrary==null) strings.status(action.label) else strings[if(action.inLibrary==true) UiText.UI_REMOVE_FROM_LIBRARY else UiText.UI_ADD_TO_LIBRARY],
            LibraryGlyph.BOOKMARK,action.inLibrary==true,enabled) {
            val current=controller.membership.value.forPublication(publication.id)
            if(current.enabled && !controller.busy.value) {
                if(current.inLibrary==true) confirm=true else changeMembership(false)
            }
        }
        if(action.inLibrary==true && entry!=null) {
            OrganizationToggle(strings[favoriteActionLabel(entry.favorite)],LibraryGlyph.HEART,entry.favorite,enabled && !library.loading) {
                val current=controller.library.value.entries.firstOrNull {it.publication.id==publication.id}
                if(current!=null && !controller.library.value.loading) controller.organize(setOf(publication.id),LibraryOrganizationChange(favorite=!current.favorite))
            }
            LibraryIconButton(strings[UiText.LIB_ORGANIZE],LibraryGlyph.TAG,enabled=enabled) {organize=true}
        }
    }
    if(membership.unavailable) TextButton(controller::refreshLibrary,Modifier.heightIn(min=48.dp)) {Text(strings[UiText.UI_RETRY_LIBRARY])}
    error?.let {Text(strings.status(it),color=MaterialTheme.colors.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}
    if(organizationResult==false) Text(strings[UiText.LIB_SAVE_ERROR],color=MaterialTheme.colors.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text(strings[UiText.UI_REMOVE_FROM_LIBRARY_CONFIRM])},
        text={Text(strings[UiText.UI_ONLY_LIBRARY_MEMBERSHIP_IS_REMOVED_FILES_HISTORY_AND_SAVED_READING_POSITIONS_ARE])},
        confirmButton={TextButton({confirm=false;changeMembership(true)},Modifier.heightIn(min=48.dp),enabled=enabled){Text(strings[UiText.UI_REMOVE])}},
        dismissButton={TextButton({confirm=false},Modifier.heightIn(min=48.dp)){Text(strings[UiText.CANCEL])}})
    if(organize && entry!=null) LibraryOrganizer(listOf(entry),tags,controller,covers) {organize=false}
}
