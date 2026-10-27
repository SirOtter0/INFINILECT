// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package org.infinilect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.infinilect.app.Destination
import org.infinilect.app.collections.*
import org.infinilect.core.*

internal fun LibraryReadingState.label() = when(this) {
    LibraryReadingState.NOT_STARTED -> UiText.LIB_NOT_STARTED
    LibraryReadingState.READING -> UiText.LIB_READING
    LibraryReadingState.COMPLETED -> UiText.LIB_COMPLETED
    LibraryReadingState.PAUSED -> UiText.LIB_PAUSED
    LibraryReadingState.ABANDONED -> UiText.LIB_ABANDONED
}
internal fun LibrarySort.label() = when(this) {
    LibrarySort.ADDED -> UiText.LIB_ADDED
    LibrarySort.LAST_READ -> UiText.LIB_LAST_READ
    LibrarySort.TITLE -> UiText.LIB_TITLE
    LibrarySort.AUTHOR -> UiText.LIB_AUTHOR
    LibrarySort.PROGRESS -> UiText.LIB_PROGRESS
}

/** Bounded viewport with separate fixed actions; safe insets, IME and large text supported. */
@Composable
internal fun LibraryPanel(title: String, close: () -> Unit, actions: @Composable FlowRowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val strings = LocalAppStrings.current
    val closeFocus = remember { FocusRequester() }
    Dialog(close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(12.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                .semantics { paneTitle = title }.onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { close(); true } else false
                }, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.padding(16.dp)) {
                    Text(title, style = MaterialTheme.typography.h6, modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        actions()
                        TextButton(close, Modifier.heightIn(min = 48.dp).focusRequester(closeFocus)) { Text(strings[UiText.CLOSE]) }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
    }
}

@Composable
private fun LibraryCheck(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Checkbox) { change(!checked) }
        .semantics { contentDescription = label; toggleableState = if(checked) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked,null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}
@Composable
private fun LibraryChoice(label: String, selected: Boolean, enabled: Boolean = true, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.RadioButton, onClick = choose)
        .semantics { this.selected = selected }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected,null, enabled = enabled); Text(label, Modifier.weight(1f).padding(start = 8.dp))
    }
}

@Composable
internal fun LibraryTools(browser: LibraryBrowser, tags: List<LibraryTag>, controller: CollectionsController, count: Int = 0) {
    val strings = LocalAppStrings.current
    val query by browser.query.collectAsState()
    var filters by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(query.text, { browser.change(query.copy(text = it)) }, Modifier.fillMaxWidth().semantics { contentDescription = strings[UiText.LIB_SEARCH] },
            placeholder = { Text(strings[UiText.LIB_SEARCH_HINT],style=MaterialTheme.typography.body2) }, singleLine = true, shape=MaterialTheme.shapes.medium,
            leadingIcon = { DestinationIcon(Destination.SEARCH, MaterialTheme.colors.onSurface) },
            trailingIcon = { if(query.text.isNotEmpty()) LibraryIconButton(strings[UiText.LIB_CLEAR_SEARCH],LibraryGlyph.CLOSE) { browser.change(query.copy(text = "")) } })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            LibraryChip(strings[UiText.LIB_ALL],!query.filtered) { browser.resetFilters() }
            LibraryChip(strings[UiText.LIB_READING],query.state==LibraryReadingState.READING) { browser.change(query.copy(state=if(query.state==LibraryReadingState.READING) null else LibraryReadingState.READING)) }
            LibraryChip(strings[UiText.LIB_COMPLETED],query.state==LibraryReadingState.COMPLETED) { browser.change(query.copy(state=if(query.state==LibraryReadingState.COMPLETED) null else LibraryReadingState.COMPLETED)) }
            LibraryChip(strings[UiText.LIB_FAVORITES],query.favorites) { browser.change(query.copy(favorites=!query.favorites)) }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(strings.quantity(UiPlural.PUBLICATIONS,count),Modifier.weight(1f),style=MaterialTheme.typography.caption)
            LibraryIconButton(strings[UiText.LIB_FILTERS],LibraryGlyph.FILTER,active=query.filtered,
                modifier=Modifier.semantics { stateDescription=strings[if(query.filtered) UiText.LIB_FILTERS_ACTIVE else UiText.LIB_ALL_STATES] }) { filters=true }
            LibraryIconButton(strings[UiText.LIB_SORT],LibraryGlyph.SORT) {sort=true}
            Box {
                LibraryIconButton(strings[UiText.LIB_MORE],LibraryGlyph.MORE) {menu=true}
                DropdownMenu(menu,{menu=false}) {
                    DropdownMenuItem({menu=false;manage=true}) {Text(strings[UiText.LIB_MANAGE_TAGS])}
                }
            }
        }
    }
    if (filters) LibraryPanel(strings[UiText.LIB_FILTERS], { filters = false }, actions = {
        TextButton(browser::resetFilters,Modifier.heightIn(min=48.dp)) { Text(strings[UiText.LIB_RESET_FILTERS]) }
    }) {
        Text(strings[UiText.LIB_READING_STATE],style=MaterialTheme.typography.subtitle2)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            LibraryChip(strings[UiText.LIB_ALL_STATES],query.state==null) {browser.change(query.copy(state=null))}
            LibraryReadingState.entries.forEach { state -> LibraryChip(strings[state.label()],query.state==state) {browser.change(query.copy(state=state))} }
        }
        Divider()
        LibraryCheck(strings[UiText.LIB_ONLY_FAVORITES],query.favorites) { browser.change(query.copy(favorites = it)) }
        LibraryCheck(strings[UiText.LIB_UNTAGGED],query.untagged) { browser.change(query.copy(untagged = it, tags = if(it) emptySet() else query.tags)) }
        if(tags.isNotEmpty()) {
            Text(strings[UiText.LIB_TAGS_ALL], style = MaterialTheme.typography.caption)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag -> LibraryChip(tag.name,tag.id in query.tags) {
                    browser.change(query.copy(tags = if(tag.id !in query.tags) query.tags + tag.id else query.tags - tag.id, untagged = false))
                } }
            }
        }
        TextButton({filters=false;manage=true},Modifier.heightIn(min=48.dp)) {Text(strings[UiText.LIB_MANAGE_TAGS])}
    }
    if (sort) LibraryPanel(strings[UiText.LIB_SORT], { sort = false }) {
        LibrarySort.entries.forEach { option -> LibraryChoice(strings[option.label()],query.sort == option) { browser.change(query.copy(sort = option)) } }
        Divider()
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            LibraryChip(strings[UiText.LIB_ASCENDING],!query.descending) {browser.change(query.copy(descending=false))}
            LibraryChip(strings[UiText.LIB_DESCENDING],query.descending) {browser.change(query.copy(descending=true))}
        }
    }
    if (manage) LibraryTagManager(controller,tags) { manage = false }
}

@Composable
internal fun LibraryOrganizer(entries: List<LibraryEntry>, tags: List<LibraryTag>, controller: CollectionsController, covers: org.infinilect.app.covers.PublicationCovers? = null, close: () -> Unit) {
    val strings = LocalAppStrings.current
    val busy by controller.busy.collectAsState()
    val single = entries.singleOrNull()
    var state by remember { mutableStateOf<LibraryReadingState?>(null) }
    var favorite by remember { mutableStateOf<Boolean?>(null) }
    var add by remember { mutableStateOf(emptySet<Long>()) }
    var remove by remember { mutableStateOf(emptySet<Long>()) }
    var manage by remember { mutableStateOf(false) }
    val ids = remember(entries) { entries.map { it.publication.id }.toSet() }
    LibraryPanel(strings[UiText.LIB_ORGANIZE],close, actions = {
        TextButton({ val known = tags.map {it.id}.toSet()
            controller.organize(ids,LibraryOrganizationChange(state,favorite,add.intersect(known),remove.intersect(known))); close() }, enabled = !busy && ids.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.LIB_APPLY]) }
    }) {
        if(single!=null) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            DetailsCover(single.publication.displayPublication(),covers)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(single.publication.title,style=MaterialTheme.typography.subtitle2)
                if(single.publication.authors.isNotEmpty()) Text(single.publication.authors.joinToString("; "),style=MaterialTheme.typography.caption)
                OrganizationToggle(strings[favoriteActionLabel(favorite ?: single.favorite)],LibraryGlyph.HEART,favorite ?: single.favorite,!busy) {favorite=!(favorite ?: single.favorite)}
            }
        } else Text(strings.selectedCount(entries.size))
        if(single == null) Text(strings[UiText.LIB_BATCH_NOTICE],style = MaterialTheme.typography.caption)
        Text(strings[UiText.LIB_READING_STATE],modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if(single == null) LibraryChip(strings[UiText.LIB_UNCHANGED],state==null,!busy) {state=null}
            LibraryReadingState.entries.forEach { option -> LibraryChip(strings[option.label()],(state ?: single?.readingState)==option,!busy) {state=option} }
        }
        Divider()
        if(single == null) {
            Text(strings[UiText.LIB_FAVORITE],modifier = Modifier.semantics { heading() })
            listOf(null,false,true).forEach { choice ->
                LibraryChoice(strings[when(choice) { null -> UiText.LIB_UNCHANGED; true -> UiText.LIB_ADD; false -> UiText.LIB_REMOVE }], favorite == choice,!busy) { favorite = choice }
            }
        }
        Text(strings[UiText.LIB_TAGS],modifier = Modifier.semantics { heading() })
        if(single!=null) FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            tags.forEach { tag ->
                val had=single.tags.any {it.id==tag.id}
                val assigned=tag.id in add || (had && tag.id !in remove)
                LibraryChip(tag.name,assigned,!busy) {
                    val checked=!assigned
                    add=if(checked && !had) add+tag.id else add-tag.id
                    remove=if(!checked && had) remove+tag.id else remove-tag.id
                }
            }
        } else tags.forEach { tag ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                val tagAction=strings[when(tag.id) {in add -> UiText.LIB_ADD;in remove -> UiText.LIB_REMOVE;else -> UiText.LIB_UNCHANGED}]
                Text(tag.name,Modifier.weight(1f),style=MaterialTheme.typography.body2)
                TextButton({when(tag.id) {
                    in add -> {add=add-tag.id;remove=remove+tag.id}
                    in remove -> remove=remove-tag.id
                    else -> add=add+tag.id
                }},enabled=!busy,modifier=Modifier.heightIn(min=48.dp).semantics {contentDescription=tag.name;stateDescription=tagAction}) {Text(tagAction)}
            }
        }
        TextButton({ manage = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(strings[UiText.LIB_CREATE_TAG]) }
    }
    if(manage) LibraryTagManager(controller,tags) { manage = false }
}

@Composable
private fun LibraryTagManager(controller: CollectionsController, tags: List<LibraryTag>, close: () -> Unit) {
    val strings = LocalAppStrings.current
    val busy by controller.busy.collectAsState()
    val result by controller.organizationResult.collectAsState()
    val library by controller.library.collectAsState()
    val counts=remember(library.entries) { buildMap<Long,Int> { library.entries.forEach { entry -> entry.tags.forEach { tag -> put(tag.id,(get(tag.id) ?: 0)+1) } } } }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<LibraryTag?>(null) }
    var deleting by remember { mutableStateOf<LibraryTag?>(null) }
    LibraryPanel(strings[UiText.LIB_MANAGE_TAGS],close) {
        Text(strings[UiText.LIB_TAG_LIMITS],style = MaterialTheme.typography.caption)
        OutlinedTextField(name,{ if(it.length <= 128) name = it },Modifier.fillMaxWidth(), singleLine = true,label = { Text(strings[UiText.LIB_TAG_NAME]) },enabled = !busy)
        FlowRow {
            TextButton({ if(editing == null) controller.createTag(name) else controller.renameTag(editing!!.id,name) },enabled = !busy && name.isNotBlank(),modifier = Modifier.heightIn(min = 48.dp)) { Text(strings[if(editing == null) UiText.LIB_CREATE_TAG else UiText.LIB_RENAME_TAG]) }
            if(editing != null) TextButton({ editing = null; name = "" }) { Text(strings[UiText.CANCEL]) }
        }
        if(result == false) Text(strings[UiText.LIB_SAVE_ERROR],color = MaterialTheme.colors.error,modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        tags.forEach { tag ->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tag.name,style=MaterialTheme.typography.body2)
                    Text(strings.quantity(UiPlural.PUBLICATIONS,counts[tag.id] ?: 0),style=MaterialTheme.typography.caption)
                }
                LibraryIconButton(strings[UiText.LIB_RENAME_TAG],LibraryGlyph.EDIT,enabled=!busy,
                    modifier=Modifier.semantics {stateDescription=tag.name}) {editing=tag;name=tag.name}
                LibraryIconButton(strings[UiText.LIB_DELETE_TAG],LibraryGlyph.DELETE,enabled=!busy,
                    modifier=Modifier.semantics {stateDescription=tag.name}) {deleting=tag}
            }
            Divider(color=MaterialTheme.colors.onSurface.copy(alpha=.08f))
        }
    }
    if(deleting != null) AlertDialog(onDismissRequest = { deleting = null },title = { Text(strings[UiText.LIB_DELETE_TAG]) },text = { Text(strings[UiText.LIB_DELETE_TAG_QUESTION]) },
        confirmButton = { TextButton({ controller.deleteTag(deleting!!.id); deleting = null },enabled = !busy) { Text(strings[UiText.LIB_DELETE_TAG]) } },
        dismissButton = { TextButton({ deleting = null }) { Text(strings[UiText.CANCEL]) } })
}
