// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
package org.infinilect.app.updates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.*
import org.infinilect.app.ui.*

internal expect fun formatUpdateTime(epoch: Long, locale: String): String
private fun failureText(value: UpdateFailure?)=when(value) {
    null -> UiText.UPDATES_PREVIOUS_ERROR
    UpdateFailure.CONNECTION -> UiText.UPDATES_CONNECTION
    UpdateFailure.TIMEOUT -> UiText.UPDATES_TIMEOUT
    UpdateFailure.RATE_LIMIT -> UiText.UPDATES_RATE_LIMIT
    UpdateFailure.HTTP -> UiText.UPDATES_HTTP
    UpdateFailure.FORMAT,UpdateFailure.REDIRECT -> UiText.UPDATES_FORMAT
    UpdateFailure.TRUST_NOT_CONFIGURED -> UiText.UPDATES_BLOCKED
    UpdateFailure.SIGNATURE,UpdateFailure.INTEGRITY -> UiText.UPDATES_UNTRUSTED
    UpdateFailure.INCOMPATIBLE -> UiText.UPDATES_INCOMPATIBLE
    UpdateFailure.STORAGE -> UiText.UPDATES_STORAGE
    UpdateFailure.PREFERENCES -> UiText.UPDATES_PREF_ERROR
    UpdateFailure.BUSY -> UiText.UPDATES_BUSY
    else -> UiText.UPDATES_INSTALL_ERROR
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateSettings(controller: UpdateController) {
    val s=LocalAppStrings.current
    val state by controller.state.collectAsState()
    val build=remember(controller) {controller.backend.installed}
    var consent by remember {mutableStateOf(false)}
    var notes by remember {mutableStateOf(false)}
    DisposableEffect(controller) {onDispose {controller.leaveScreen()}}
    val busy=state.stage in setOf(UpdateStage.CHECKING,UpdateStage.DOWNLOADING,UpdateStage.VERIFYING)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(s[UiText.UPDATES_TITLE],style=MaterialTheme.typography.h6,modifier=Modifier.semantics {heading()})
        Text(s.format(UiText.UPDATES_INSTALLED,build.versionName,build.versionCode),style=MaterialTheme.typography.body2)
        Text(s[UiText.UPDATES_CHANNEL],style=MaterialTheme.typography.subtitle2)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            UpdateChannel.entries.forEach {channel ->
                val label=s[if(channel==UpdateChannel.STABLE) UiText.UPDATES_STABLE else UiText.UPDATES_BETA]
                OutlinedButton({controller.channel(channel)},enabled=state.loaded && !busy,
                    colors=ButtonDefaults.outlinedButtonColors(backgroundColor=if(state.preferences.channel==channel) MaterialTheme.colors.primary.copy(alpha=.16f) else MaterialTheme.colors.surface),
                    modifier=Modifier.heightIn(min=48.dp).semantics {selected=state.preferences.channel==channel}) {Text(label)}
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(state.preferences.automatic,enabled=state.loaded,role=Role.Checkbox,onValueChange=controller::automatic),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(state.preferences.automatic,null,enabled=state.loaded,modifier=Modifier.clearAndSetSemantics {})
            Text(s[UiText.UPDATES_AUTOMATIC],Modifier.weight(1f))
        }
        Text(s[UiText.UPDATES_AUTO_NOTICE],style=MaterialTheme.typography.caption)
        if(state.preferences.lastAttempt>0) {
            val time=remember(state.preferences.lastAttempt,s.localeTag) {formatUpdateTime(state.preferences.lastAttempt,s.localeTag)}
            Text(s.format(UiText.UPDATES_CHECKED,time),style=MaterialTheme.typography.caption)
        }
        Text(s[when(state.stage) {
            UpdateStage.IDLE -> UiText.UPDATES_IDLE
            UpdateStage.CHECKING -> UiText.UPDATES_CHECKING
            UpdateStage.CURRENT -> UiText.UPDATES_CURRENT
            UpdateStage.AVAILABLE -> UiText.UPDATES_AVAILABLE
            UpdateStage.DOWNLOADING -> UiText.UPDATES_DOWNLOADING
            UpdateStage.VERIFYING -> UiText.UPDATES_VERIFYING
            UpdateStage.READY -> UiText.UPDATES_READY
            UpdateStage.PERMISSION -> UiText.UPDATES_PERMISSION
            UpdateStage.DELEGATED -> UiText.UPDATES_DELEGATED
            UpdateStage.CANCELLED -> UiText.UPDATES_CANCELLED
            UpdateStage.ERROR -> failureText(state.failure)
        }],modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
        if(state.saveFailed) Text(s[UiText.UPDATES_PREF_ERROR],color=MaterialTheme.colors.error)
        if(!controller.backend.trustedInstallationConfigured && build.android) Text(s[UiText.UPDATES_BLOCKED],style=MaterialTheme.typography.caption)
        if(busy) {
            if(state.stage==UpdateStage.DOWNLOADING && state.total>0) {
                LinearProgressIndicator((state.downloaded.toFloat()/state.total).coerceIn(0f,1f),Modifier.fillMaxWidth())
                Text(s.format(UiText.UPDATES_PROGRESS,s.number(state.downloaded/1_048_576.0,1),s.number(state.total/1_048_576.0,1)),style=MaterialTheme.typography.caption)
            } else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(controller::cancel,Modifier.heightIn(min=48.dp)) {Text(s[UiText.CANCEL])}
        } else Button({controller.check()},enabled=state.loaded,modifier=Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_CHECK])}
        state.release?.let {release ->
            Text(s.format(UiText.UPDATES_VERSION,release.tag),style=MaterialTheme.typography.subtitle1)
            val size=release.assets.filter {it.name.endsWith(".apk")}.singleOrNull()?.size
            if(size!=null) Text(s.format(UiText.UPDATES_SIZE,s.number(size/1_048_576.0,1)),style=MaterialTheme.typography.caption)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton({notes=true},Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_NOTES])}
                TextButton(controller::later,Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_LATER])}
                if(build.android && state.stage !in setOf(UpdateStage.READY,UpdateStage.PERMISSION,UpdateStage.DELEGATED)) Button({consent=true},enabled=!busy && controller.backend.trustedInstallationConfigured,modifier=Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_UPDATE])}
            }
        }
        if(state.stage==UpdateStage.READY || state.stage==UpdateStage.PERMISSION) {
            if(state.stage==UpdateStage.PERMISSION) TextButton(controller::permission,Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_ALLOW])}
            Button(controller::install,modifier=Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_INSTALL])}
        }
        TextButton(controller::openOfficialPage,Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_OFFICIAL])}
    }
    if(consent) AlertDialog(onDismissRequest={consent=false},title={Text(s[UiText.UPDATES_UPDATE])},text={Text(s[UiText.UPDATES_CONSENT])},
        confirmButton={TextButton({consent=false;controller.download()},Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_DOWNLOAD])}},
        dismissButton={TextButton({consent=false},Modifier.heightIn(min=48.dp)) {Text(s[UiText.CANCEL])}})
    if(notes) AlertDialog(onDismissRequest={notes=false},title={Text(s[UiText.UPDATES_NOTES])},
        text={Column(Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState())) {Text(state.release?.notes?.ifBlank {s[UiText.UPDATES_NO_NOTES]} ?: s[UiText.UPDATES_NO_NOTES])}},
        confirmButton={TextButton({notes=false},Modifier.heightIn(min=48.dp)) {Text(s[UiText.CLOSE])}})
}

/** Observe only notice visibility, not download progress, in the application shell. */
@Composable
internal fun UpdateNotice(controller: UpdateController, open: ()->Unit) {
    val notice by remember(controller) {controller.state.map {it.notice}.distinctUntilChanged()}.collectAsState(false)
    if(notice) {
        val s=LocalAppStrings.current
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
            TextButton(open,Modifier.weight(1f).heightIn(min=48.dp)) {Text(s[UiText.UPDATES_NOTICE])}
            TextButton(controller::later,Modifier.heightIn(min=48.dp)) {Text(s[UiText.UPDATES_LATER])}
        }
    }
}
