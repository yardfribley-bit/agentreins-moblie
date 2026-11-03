package com.wangzan101.mika.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
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
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import com.wangzan101.mika.vault.VaultNoteRow
import com.wangzan101.mika.vault.VaultRuntime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private class OpenPersistableTree : ActivityResultContract<Uri?, Uri?>() {
    override fun createIntent(context: Context, input: Uri?) = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (input != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/** 设置里选出 Obsidian 库,并打开阅读、写回、运行日志。 */
@Composable
fun VaultSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { (context.applicationContext as MikaApp).vault }
    val prefs by runtime.settings.state.collectAsState()
    var subfolder by remember { mutableStateOf(prefs.subfolder) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(OpenPersistableTree()) { uri -> if (uri != null) runtime.choose(uri) }
    val chosen = prefs.treeUri != null
    PageScaffold(stringResource(R.string.vault_title), onBack) {
        GroupCard {
            ListRow(null, stringResource(R.string.vault_folder), if (chosen) prefs.displayName ?: stringResource(R.string.vault_picked) else stringResource(R.string.vault_none), first = true)
            ListRow(null, if (chosen) stringResource(R.string.vault_change) else stringResource(R.string.vault_pick), onClick = {
                pick.launch(prefs.treeUri?.let(Uri::parse))
            })
        }
        GroupLabel(stringResource(R.string.vault_subfolder))
        GroupCard {
            InputRow(stringResource(R.string.vault_subfolder), subfolder, {
                subfolder = it
                runtime.settings.update { cur -> cur.copy(subfolder = it) }
            }, stringResource(R.string.vault_subfolder_hint), first = true)
        }
        GroupLabel(stringResource(R.string.memory_switches))
        GroupCard {
            SwitchRow(stringResource(R.string.vault_read), stringResource(R.string.vault_read_sub), chosen && prefs.readEnabled, true) {
                if (chosen) runtime.settings.update { cur -> cur.copy(readEnabled = it) }
            }
            SwitchRow(stringResource(R.string.vault_write), stringResource(R.string.vault_write_sub), chosen && prefs.writeEnabled) {
                if (chosen) runtime.settings.update { cur -> cur.copy(writeEnabled = it) }
            }
            SwitchRow(stringResource(R.string.vault_log), stringResource(R.string.vault_log_sub), chosen && prefs.logEnabled) {
                if (chosen) runtime.settings.update { cur -> cur.copy(logEnabled = it) }
            }
        }
        GroupLabel(stringResource(R.string.vault_status))
        GroupCard {
            ListRow(null, stringResource(R.string.vault_last_read), statusLine(prefs.lastReadAt, prefs.lastReadOk, prefs.lastReadDetail, true), first = true)
            ListRow(null, stringResource(R.string.vault_last_write), statusLine(prefs.lastWriteAt, prefs.lastWriteOk, prefs.lastWriteDetail, false))
        }
        MikaButton(if (busy) stringResource(R.string.vault_testing) else stringResource(R.string.vault_test), {
            if (!chosen || busy) return@MikaButton
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runtime.probe() }
                message = if (result.ok) context.getString(R.string.vault_test_ok) else context.getString(R.string.vault_test_fail, result.detail ?: result.code ?: "")
                busy = false
            }
        }, kind = ButtonKind.Tonal, size = ButtonSize.Small, modifier = Modifier.padding(horizontal = 16.dp))
        message?.let { FooterNote(it) }
        FooterNote(stringResource(R.string.vault_footer))
    }
}

/** 记忆页里的库笔记:按应用、任务、运行列出,点开是 Markdown。 */
@Composable
fun VaultNotesScreen(initialPath: String?, missingRun: Boolean = false, onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { (context.applicationContext as MikaApp).vault }
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<VaultNoteRow>>(emptyList()) }
    var open by remember { mutableStateOf(initialPath) }
    var body by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(query, open, missingRun) {
        if (missingRun && initialPath == null) return@LaunchedEffect
        if (open == null) rows = withContext(Dispatchers.IO) { runtime.browse(query) }
        else body = withContext(Dispatchers.IO) { runtime.readNote(open!!) }
    }
    if (missingRun && initialPath == null) {
        PageScaffold(stringResource(R.string.vault_open_note), onBack) { FooterNote(stringResource(R.string.vault_note_missing)) }
        return
    }
    if (open != null) {
        BackHandler { if (initialPath != null) onBack() else open = null }
        PageScaffold(open!!.substringAfterLast('/'), { if (initialPath != null) onBack() else open = null }) {
            val text = body
            if (text == null) FooterNote(stringResource(R.string.vault_note_missing))
            else {
                val touch = remember(open) { runtime.touch(open!!) }
                Text(readWriteLine(touch.readAt, touch.writeAt), Modifier.padding(top = 8.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                if (touch.readRuns.isNotEmpty()) Text(stringResource(R.string.vault_read_by, touch.readRuns.joinToString(" ")), Modifier.padding(top = 4.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                if (touch.writeRuns.isNotEmpty()) Text(stringResource(R.string.vault_written_by, touch.writeRuns.joinToString(" ")), Modifier.padding(top = 2.dp), style = MikaType.Hint, color = MikaTheme.colors.ink3)
                MarkdownText(displayWiki(text), MikaType.Body, Modifier.padding(top = 12.dp).fillMaxWidth())
            }
        }
        return
    }
    PageScaffold(stringResource(R.string.vault_notes), onBack) {
        SearchField(query, { query = it }, stringResource(R.string.search))
        if (rows.isEmpty()) FooterNote(if (runtime.settings.state.value.treeUri == null) stringResource(R.string.vault_need_folder) else stringResource(R.string.vault_empty))
        listOf("应用", "任务", "运行", "其他").forEach { group ->
            val items = rows.filter { it.group == group }
            if (items.isEmpty()) return@forEach
            GroupLabel(groupLabel(group))
            GroupCard {
                items.forEachIndexed { i, row ->
                    ListRow(null, row.title, rowSubtitle(row), first = i == 0, onClick = { open = row.path })
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, on: Boolean, first: Boolean = false, onChange: (Boolean) -> Unit) {
    ListRow(null, title, subtitle, first = first) { MikaSwitch(on, onChange) }
}

@Composable
private fun statusLine(at: Long?, ok: Boolean?, detail: String?, reading: Boolean): String {
    if (at == null || ok == null) return stringResource(R.string.vault_status_none)
    val time = DateTimeFormatter.ofPattern("M-d HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
    val body = when {
        !ok && detail == "permission" -> stringResource(R.string.vault_perm)
        !ok -> detail?.ifBlank { null } ?: stringResource(R.string.vault_failed)
        reading && detail == "0" -> stringResource(R.string.vault_read_empty)
        reading && detail != null && detail.all { it.isDigit() } -> stringResource(R.string.vault_read_n, detail.toInt())
        else -> detail?.substringAfterLast('/') ?: stringResource(R.string.vault_ok)
    }
    return "$time · $body"
}

@Composable
private fun rowSubtitle(row: VaultNoteRow): String {
    val read = row.readAt?.let { stringResource(R.string.vault_row_read, clock(it)) }
    val write = row.writeAt?.takeIf { it > 0 }?.let { stringResource(R.string.vault_row_write, clock(it)) }
    return listOfNotNull(read, write).joinToString("  ").ifBlank { row.path.substringAfterLast('/') }
}

@Composable
private fun readWriteLine(readAt: Long?, writeAt: Long?): String = listOfNotNull(
    readAt?.let { stringResource(R.string.vault_row_read, clock(it)) },
    writeAt?.let { stringResource(R.string.vault_row_write, clock(it)) },
).joinToString("  ")

private fun clock(at: Long) = DateTimeFormatter.ofPattern("M-d HH:mm").format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))

@Composable
private fun groupLabel(group: String) = when (group) {
    "应用" -> stringResource(R.string.vault_group_apps)
    "任务" -> stringResource(R.string.vault_group_tasks)
    "运行" -> stringResource(R.string.vault_group_runs)
    else -> stringResource(R.string.vault_group_other)
}

/** 应用里把双链收成可读的字,文件本身仍是 Obsidian 双链。 */
internal fun displayWiki(md: String): String = md
    .replace(Regex("""\[\[([^\]|]+)\|([^\]]+)\]\]"""), "$2")
    .replace(Regex("""\[\[([^\]]+)\]\]"""), "$1")
