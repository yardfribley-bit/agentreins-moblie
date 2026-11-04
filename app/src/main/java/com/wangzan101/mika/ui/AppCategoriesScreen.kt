package com.wangzan101.mika.ui

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.agent.RelevantApps
import com.wangzan101.mika.settings.AppCategoryStore
import com.wangzan101.mika.ui.components.PageScaffold

@Composable
fun AppCategoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { AppCategoryStore(context) }
    var revision by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    var category by remember { mutableStateOf(RelevantApps.categories.first()) }
    val apps = remember(revision) {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        context.packageManager.queryIntentActivities(launcher, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            pkg to info.loadLabel(context.packageManager).toString()
        }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val labels = mapOf("shopping and local deals" to "购物与团购", "coding and development" to "编程与开发",
        "research and search" to "查询与搜索", "X and content creation" to "X 与内容创作", "maps" to "地图导航",
        "travel" to "出行交通", "communication" to "通讯", "finance" to "金融", "productivity" to "办公与笔记", "media" to "影音")
    val allCategories = RelevantApps.categories + "uncategorized"
    fun belongs(app: Pair<String, String>, id: String): Boolean {
        val cats = store.categories(app.first, app.second)
        return if (id == "uncategorized") cats.isEmpty() else id in cats
    }
    fun label(id: String) = labels[id] ?: if (id == "uncategorized") "未分类" else id
    PageScaffold("应用分类", onBack) {
        Text("自动整理手机应用，可多选分类。新增应用重新打开本页即可看到。", modifier = Modifier.padding(vertical = 12.dp))
        allCategories.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { id ->
                    val count = apps.count { belongs(it, id) }
                    if (category == id) FilledTonalButton(onClick = { category = id }, modifier = Modifier.weight(1f)) { Text("${label(id)} · $count") }
                    else OutlinedButton(onClick = { category = id }, modifier = Modifier.weight(1f)) { Text("${label(id)} · $count") }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text("${label(category)} · ${apps.count { belongs(it, category) }} 个应用", modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        apps.filter { belongs(it, category) }.forEach { (pkg, name) ->
            val cats = store.categories(pkg, name).mapNotNull(labels::get).joinToString("、").ifEmpty { "未分类" }
            ListItem(headlineContent = { Text(name) }, supportingContent = { Text("$cats · ${if (store.confirmed(pkg)) "已确认" else "自动分类"}") },
                trailingContent = { TextButton(onClick = { selected = pkg to name }) { Text("调整") } })
        }
        Text("按任务提供相关应用给模型；已安装不代表已登录或可操作。", modifier = Modifier.padding(vertical = 16.dp))
    }
    selected?.let { (pkg, name) ->
        var chosen by remember(pkg) { mutableStateOf(store.categories(pkg, name)) }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(name) }, text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                RelevantApps.categories.forEach { id ->
                    Row(horizontalArrangement = Arrangement.Start) {
                        Checkbox(checked = id in chosen, onCheckedChange = { chosen = if (it) chosen + id else chosen - id })
                        Text(labels[id] ?: id, modifier = Modifier.padding(top = 14.dp))
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { store.save(pkg, chosen); revision++; selected = null }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } })
    }
}
