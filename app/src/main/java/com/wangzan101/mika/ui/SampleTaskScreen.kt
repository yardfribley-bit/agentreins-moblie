package com.wangzan101.mika.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.ProgressDots
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 引导最后一步：一条只用模型、不操作手机的示例任务。 */
@Composable
fun SampleTaskScreen(onBack: () -> Unit, onSkip: () -> Unit, onRun: (String) -> Unit) {
    val task = stringResource(R.string.sample_task_text)
    val c = MikaTheme.colors
    BackHandler(onBack = onBack)
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
            TopBar(stringResource(R.string.sample_title), MikaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.sample_lead), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2)
                GroupCard {
                    Text(task, Modifier.padding(16.dp), style = MikaType.Body, color = c.ink)
                }
                Text(stringResource(R.string.sample_note), Modifier.padding(top = 4.dp), style = MikaType.Caption, color = c.ink3)
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Sample), Modifier.padding(bottom = 10.dp))
                MikaButton(stringResource(R.string.sample_run), { onRun(task) }, Modifier.fillMaxWidth().testTag("sample-run"))
                MikaButton(stringResource(R.string.sample_skip), onSkip, Modifier.fillMaxWidth().testTag("sample-skip"), kind = ButtonKind.Text, size = ButtonSize.Medium)
            }
        }
    }
}
