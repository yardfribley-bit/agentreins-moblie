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
import com.wangzan101.mika.settings.KeystoreKeyCipher
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
    var status by remember { mutableStateOf("尚未测试连接") }
    var busy by remember { mutableStateOf(false) }
    var candidate by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun connect(trusted: String) {
        val p = port.toIntOrNull() ?: return
        val sameAccount = prefs.getString("host", "") == host.trim() && prefs.getString("port", "") == port && prefs.getString("user", "") == user.trim()
        val encrypted = if (password.isNotEmpty()) runCatching { Base64.getEncoder().encodeToString(cipher.encrypt(password.toByteArray())) }.getOrNull() else if (sameAccount) prefs.getString("password", null) else null
        if (encrypted == null) { status = "请填写密码，或检查手机密钥库是否可用"; return }
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
        AlertDialog(onDismissRequest = { candidate = null }, title = { Text("确认服务器身份") },
            text = { Text("首次连接 $host:$port。请与服务商提供的服务器指纹核对：\n$presented\n确认后本机会记住该身份。") },
            confirmButton = { TextButton(onClick = { candidate = null; connect(presented) }) { Text("确认并连接") } },
            dismissButton = { TextButton(onClick = { candidate = null }) { Text("取消") } })
    }
    PageScaffold("云工作台", onBack) {
        StateCard(MikaIcons.Info, "连接自己的云主机", "输入地址和账号即可开始。首次连接时会请你确认服务器身份；此版本暂不部署长期任务。", TileTone.Warn)
        GroupCard {
            InputRow("地址", host, { if (host != it) fingerprint = ""; host = it }, "域名或 IP", first = true, keyboard = KeyboardType.Uri)
            InputRow("端口", port, { if (port != it) fingerprint = ""; port = it }, "22", keyboard = KeyboardType.Number)
            InputRow("用户名", user, { user = it }, "例如 ubuntu")
            InputRow("密码", password, { password = it }, if (hasPassword) "已保存，留空保持不变" else "SSH 密码", secret = true)
        }
        MikaButton(if (busy) "正在连接…" else "保存并测试连接", {
            val p = port.toIntOrNull()
            if (host.isBlank() || user.isBlank() || p == null || p !in 1..65535) {
                status = "请填写地址、有效端口和用户名"
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
        GroupCard { ListRow(null, "连接状态", status, first = true) }
        MikaButton("删除已保存的主机和密码", {
            prefs.edit().clear().apply(); host = ""; port = "22"; user = ""; fingerprint = ""; password = ""; hasPassword = false; status = "已删除"
        }, Modifier.fillMaxWidth().padding(vertical = 12.dp), kind = ButtonKind.Text, enabled = !busy)
    }
}
