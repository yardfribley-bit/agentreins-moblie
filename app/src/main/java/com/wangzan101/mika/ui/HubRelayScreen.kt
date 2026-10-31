package com.wangzan101.mika.ui

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.relay.HubRelayRuntime
import com.wangzan101.mika.relay.HubRelaySnapshot
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.InputRow
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StateCard
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置 › Hub Relay:加入管理者已经建好的会话,把任务进度写进去。手机不创建会话。 */
@Composable
fun HubRelayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { (context.applicationContext as MikaApp).hubRelay }
    val rev by runtime.revision.collectAsState()
    val live = remember(rev) { runtime.snapshot() }
    val initial = remember { runtime.snapshot() }
    var mcpUrl by remember { mutableStateOf(initial.mcpUrl) }
    var tokenDraft by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf(initial.joinCode) }
    var alias by remember { mutableStateOf(initial.alias) }
    var agentType by remember { mutableStateOf(initial.agentType) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val latestToken = rememberUpdatedState(tokenDraft)
    // 输入过程中不覆盖已保存的令牌;离开页面或点加入时,草稿非空才写入。
    DisposableEffect(runtime) {
        onDispose { if (latestToken.value.isNotBlank()) runtime.setToken(latestToken.value) }
    }

    PageScaffold(stringResource(R.string.hub_title), onBack) {
        StateCard(MikaIcons.Info, stringResource(R.string.hub_privacy_title), stringResource(R.string.hub_privacy_body), TileTone.Warn)
        GroupCard {
            ListRow(null, stringResource(R.string.hub_enable), stringResource(R.string.hub_enable_sub), first = true) {
                MikaSwitch(live.enabled, { runtime.setEnabled(it) }, Modifier.testTag("hub-enabled"))
            }
        }
        FooterNote(stringResource(R.string.hub_fixed_session))

        GroupLabel(stringResource(R.string.hub_connection))
        GroupCard {
            InputRow(stringResource(R.string.hub_base_url), mcpUrl, {
                mcpUrl = it
                runtime.setMcpUrl(it)
            }, stringResource(R.string.hub_base_hint), first = true, keyboard = KeyboardType.Uri, labelWidth = 96.dp)
            InputRow(
                stringResource(R.string.hub_token), tokenDraft,
                { tokenDraft = it },
                if (live.hasToken) stringResource(R.string.hub_token_saved) else stringResource(R.string.hub_token_hint),
                secret = true,
                labelWidth = 96.dp,
                trailing = if (live.hasToken || tokenDraft.isNotEmpty()) {
                    {
                        MikaButton(stringResource(R.string.hub_token_clear), {
                            tokenDraft = ""
                            runtime.clearToken()
                        }, kind = ButtonKind.Text, size = ButtonSize.Small)
                    }
                } else null,
            )
            InputRow(stringResource(R.string.hub_code), joinCode, {
                joinCode = it
                runtime.setJoinCode(it)
            }, stringResource(R.string.hub_code_hint), labelWidth = 96.dp)
            InputRow(stringResource(R.string.hub_alias), alias, {
                alias = it
                runtime.setAlias(it)
            }, stringResource(R.string.hub_alias_hint), labelWidth = 96.dp)
            InputRow(stringResource(R.string.hub_agent_type), agentType, {
                agentType = it
                runtime.setAgentType(it)
            }, stringResource(R.string.hub_agent_type_hint), labelWidth = 96.dp)
        }
        FooterNote(stringResource(R.string.hub_connect_hint))
        GroupCard {
            ListRow(null, stringResource(R.string.hub_shots), stringResource(R.string.hub_shots_sub), first = true) {
                MikaSwitch(live.uploadScreenshots, { runtime.setUploadScreenshots(it) }, Modifier.testTag("hub-shots"))
            }
            ListRow(null, stringResource(R.string.hub_claim), stringResource(R.string.hub_claim_sub)) {
                MikaSwitch(live.claimTasks, { runtime.setClaimTasks(it) }, Modifier.testTag("hub-claim"))
            }
        }
        MikaButton(
            if (busy) stringResource(R.string.hub_joining) else stringResource(R.string.hub_join),
            {
                if (!busy && live.enabled) {
                    if (tokenDraft.isNotBlank()) runtime.setToken(tokenDraft)
                    busy = true
                    scope.launch {
                        withContext(Dispatchers.IO) { runtime.joinNow() }
                        busy = false
                    }
                }
            },
            Modifier.fillMaxWidth().padding(bottom = 12.dp).testTag("hub-join"),
            enabled = live.enabled && !busy,
        )

        GroupLabel(stringResource(R.string.hub_status))
        GroupCard {
            ListRow(
                null, stringResource(R.string.hub_status),
                live.sessionId.takeIf { it.isNotBlank() }?.let { stringResource(R.string.hub_session, it) },
                first = true,
            ) { RowValue(statusLabel(live)) }
            live.lastError?.let { ListRow(null, stringResource(R.string.hub_last_error), errorLabel(it)) }
            ListRow(null, stringResource(R.string.hub_agent_id)) {
                RowValue(live.agentId.ifBlank { stringResource(R.string.hub_agent_unknown) })
            }
            ListRow(null, stringResource(R.string.hub_last_seq)) {
                RowValue(if (live.lastSeq > 0) live.lastSeq.toString() else stringResource(R.string.hub_seq_none))
            }
        }
        FooterNote(stringResource(R.string.hub_setup_hint))
    }
}

@Composable
private fun statusLabel(snap: HubRelaySnapshot): String = when {
    !snap.enabled -> stringResource(R.string.hub_off)
    snap.lastError != null -> stringResource(R.string.hub_error)
    snap.sessionId.isNotBlank() -> stringResource(R.string.hub_connected)
    else -> stringResource(R.string.hub_not_joined)
}

@Composable
private fun errorLabel(code: String): String = when (code) {
    "need token" -> stringResource(R.string.hub_need_token)
    "need join code" -> stringResource(R.string.hub_need_code)
    "token unreadable" -> stringResource(R.string.hub_token_unreadable)
    "network" -> stringResource(R.string.hub_network)
    "off" -> stringResource(R.string.hub_off)
    "missing session id" -> stringResource(R.string.hub_missing_session)
    else -> code
}
