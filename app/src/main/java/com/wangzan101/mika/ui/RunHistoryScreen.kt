package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.record.RunSummary
import com.wangzan101.mika.record.RunStore
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** 最近保留的运行记录；点开后查看具体过程、结果和素材。 */
@Composable
fun RunHistoryScreen(store: RunStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val stableStore = remember { store }
    val runs by produceState<List<RunSummary>?>(null, stableStore) {
        value = withContext(Dispatchers.IO) { stableStore.list() }
    }
    PageScaffold(stringResource(R.string.run_history_title), onBack) {
        val rows = runs
        if (rows == null) return@PageScaffold
        if (rows.isEmpty()) {
            EmptyNote(stringResource(R.string.run_history_empty), Modifier.padding(top = 48.dp))
        } else {
            Column {
                Text(stringResource(R.string.run_history_note), Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MikaType.Hint, color = MikaTheme.colors.ink3)
                Column {
                    rows.forEach { run ->
                        val time = remember(run.startedAtMs) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(run.startedAtMs)) }
                        val state = when (run.outcome) {
                            "succeeded" -> stringResource(R.string.run_history_succeeded)
                            "unverified" -> stringResource(R.string.run_history_unverified)
                            null -> stringResource(R.string.run_history_running)
                            else -> stringResource(R.string.run_history_incomplete)
                        }
                        val c = MikaTheme.colors
                        GroupCard(Modifier.padding(bottom = 10.dp)) {
                            Column(Modifier.fillMaxWidth().clickable { onOpen(run.id) }.padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(
                                            if (run.outcome == "succeeded") c.ok else if (run.endedAtMs == null) c.accent else c.warnSolid, CircleShape))
                                        Text(state, style = MikaType.Label, color = c.ink2)
                                    }
                                    Text(time, style = MikaType.Hint, color = c.ink3)
                                }
                                Text(run.goal.ifBlank { stringResource(R.string.run_history_untitled) },
                                    Modifier.padding(top = 10.dp), style = MikaType.Body, color = c.ink)
                                Text(stringResource(R.string.run_history_steps, run.steps),
                                    Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(c.fill).padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MikaType.Hint, color = c.ink2)
                            }
                        }
                    }
                }
            }
        }
    }
}
