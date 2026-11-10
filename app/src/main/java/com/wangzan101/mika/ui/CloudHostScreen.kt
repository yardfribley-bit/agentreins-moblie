package com.wangzan101.mika.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.KeystoreKeyCipher
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.*
import com.wangzan101.mika.ui.theme.MikaIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64

@Composable
fun CloudHostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cloud_host_v1", Context.MODE_PRIVATE) }
    val cipher = remember { KeystoreKeyCipher("cloud-host-v1") }
    var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty()) }
    var port by remember { mutableStateOf(prefs.getString("port", "22").orEmpty()) }
    var user by remember { mutableStateOf(prefs.getString("user", "").orEmpty()) }
    var fingerprint by remember { mutableStateOf(prefs.getString("fingerprint", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var hasPassword by remember { mutableStateOf(prefs.contains("password")) }
    var status by remember { mutableStateOf(context.getString(R.string.cloud_status_idle)) }
    var busy by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun connect(trusted: String) {
        val p = port.toIntOrNull() ?: return
        val sameAccount = prefs.getString("host", "") == host.trim() && prefs.getString("port", "") == port && prefs.getString("user", "") == user.trim()
        val encrypted = if (password.isNotEmpty()) runCatching { Base64.getEncoder().encodeToString(cipher.encrypt(password.toByteArray())) }.getOrNull() else if (sameAccount) prefs.getString("password", null) else null
        if (encrypted == null) { status = context.getString(R.string.cloud_need_password); return }
        prefs.edit().putString("host", host.trim()).putString("port", port).putString("user", user.trim()).putString("fingerprint", trusted).putString("password", encrypted).apply()
        fingerprint = trusted
        hasPassword = true
        password = ""
        busy = true
        val h = host.trim(); val u = user.trim()
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val secret = String(cipher.decrypt(Base64.getDecoder().decode(encrypted)))
                    CloudSsh.test(h, p, u, secret, trusted)
                }.getOrElse(CloudSsh::failureMessage)
            }
            busy = false
        }
    }
    candidate?.let { presented ->
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text(stringResource(R.string.cloud_trust_title)) },
            text = { Text(stringResource(R.string.cloud_trust_body, host, port, presented)) },
            confirmButton = { TextButton(onClick = { candidate = null; connect(presented) }) { Text(stringResource(R.string.cloud_trust_confirm)) } },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text(stringResource(R.string.cancel)) } })
    }
    PageScaffold(stringResource(R.string.cloud_title), onBack) {
        StateCard(MikaIcons.Info, stringResource(R.string.cloud_card_title), stringResource(R.string.cloud_card_body), TileTone.Warn)
        GroupCard {
            InputRow(stringResource(R.string.cloud_host), host, { if (host != it) fingerprint = ""; host = it }, stringResource(R.string.cloud_host_hint), first = true, keyboard = KeyboardType.Uri)
            InputRow(stringResource(R.string.cloud_port), port, { if (port != it) fingerprint = ""; port = it }, "22", keyboard = KeyboardType.Number)
            InputRow(stringResource(R.string.cloud_user), user, { user = it }, stringResource(R.string.cloud_user_hint))
            InputRow(stringResource(R.string.cloud_password), password, { password = it }, if (hasPassword) stringResource(R.string.cloud_password_saved) else stringResource(R.string.cloud_password_hint), secret = true)
        }
        MikaButton(if (busy) stringResource(R.string.cloud_connecting) else stringResource(R.string.cloud_test), {
            val p = port.toIntOrNull()
            if (host.isBlank() || user.isBlank() || p == null || p !in 1..65535) {
                status = context.getString(R.string.cloud_need_fields)
            } else {
                if (fingerprint.isNotEmpty()) connect(fingerprint)
                else {
                    busy = true
                    val h = host.trim(); val u = user.trim()
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { CloudSsh.probe(h, p, u) } }
                        candidate = result.getOrNull()
                        if (candidate == null) status = CloudSsh.failureMessage(result.exceptionOrNull() ?: IllegalStateException())
                        busy = false
                    }
                }
            }
        }, Modifier.fillMaxWidth().padding(vertical = 12.dp), enabled = !busy)
        GroupCard { ListRow(null, stringResource(R.string.cloud_status), status, first = true) }
        MikaButton(stringResource(R.string.cloud_forget), {
            prefs.edit().clear().apply(); host = ""; port = "22"; user = ""; fingerprint = ""; password = ""; hasPassword = false; status = context.getString(R.string.cloud_forgotten)
        }, Modifier.fillMaxWidth().padding(vertical = 12.dp), kind = ButtonKind.Text, enabled = !busy)
    }
}
