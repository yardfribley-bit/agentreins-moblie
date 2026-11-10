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
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
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
    val labels = mapOf(
        "shopping and local deals" to stringResource(R.string.cat_shopping),
        "coding and development" to stringResource(R.string.cat_coding),
        "research and search" to stringResource(R.string.cat_research),
        "X and content creation" to stringResource(R.string.cat_x),
        "maps" to stringResource(R.string.cat_maps),
        "travel" to stringResource(R.string.cat_travel),
        "communication" to stringResource(R.string.cat_comms),
        "finance" to stringResource(R.string.cat_finance),
        "productivity" to stringResource(R.string.cat_productivity),
        "media" to stringResource(R.string.cat_media),
    )
    val uncategorized = stringResource(R.string.cat_none)
    val allCategories = RelevantApps.categories + "uncategorized"
    fun belongs(app: Pair<String, String>, id: String): Boolean {
        val cats = store.categories(app.first, app.second)
        return if (id == "uncategorized") cats.isEmpty() else id in cats
    }
    fun label(id: String) = labels[id] ?: if (id == "uncategorized") uncategorized else id
    PageScaffold(stringResource(R.string.cat_title), onBack) {
        Text(stringResource(R.string.cat_lead), modifier = Modifier.padding(vertical = 12.dp))
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
        val sep = stringResource(R.string.list_sep)
        val confirmedLabel = stringResource(R.string.cat_confirmed)
        val autoLabel = stringResource(R.string.cat_auto)
        val adjustLabel = stringResource(R.string.cat_adjust)
        Text(stringResource(R.string.cat_heading, label(category), apps.count { belongs(it, category) }), modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        apps.filter { belongs(it, category) }.forEach { (pkg, name) ->
            val cats = store.categories(pkg, name).mapNotNull(labels::get).joinToString(sep).ifEmpty { uncategorized }
            val state = if (store.confirmed(pkg)) confirmedLabel else autoLabel
            ListItem(headlineContent = { Text(name) }, supportingContent = { Text(context.getString(R.string.cat_line, cats, state)) },
                trailingContent = { TextButton(onClick = { selected = pkg to name }) { Text(adjustLabel) } })
        }
        Text(stringResource(R.string.cat_foot), modifier = Modifier.padding(vertical = 16.dp))
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
        }, confirmButton = { TextButton(onClick = { store.save(pkg, chosen); revision++; selected = null }) { Text(stringResource(R.string.cat_confirm)) } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
