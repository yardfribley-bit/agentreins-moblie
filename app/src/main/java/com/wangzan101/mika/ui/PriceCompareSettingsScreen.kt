package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.settings.PriceCompareStore
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 比价平台名单：一行一个显示名和包名。 */
@Composable
fun PriceCompareSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = PriceCompareStore(context)
    var text by rememberSaveable { mutableStateOf(store.text()) }
    var message by rememberSaveable { mutableStateOf("") }
    val saved = stringResource(R.string.settings_price_compare_saved)
    val skipped = stringResource(R.string.settings_price_compare_skipped)
    val fallback = stringResource(R.string.settings_price_compare_fallback)
    PageScaffold(stringResource(R.string.settings_price_compare), onBack) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(stringResource(R.string.settings_price_compare_body), style = MikaType.Body, color = MikaTheme.colors.ink2)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                minLines = 6,
                label = { Text(stringResource(R.string.settings_price_compare)) },
                placeholder = { Text(stringResource(R.string.settings_price_compare_hint)) },
            )
            if (message.isNotEmpty()) {
                Text(message, Modifier.padding(top = 8.dp), style = MikaType.Caption, color = MikaTheme.colors.ink2)
            }
            MikaButton(stringResource(R.string.settings_price_compare_save), {
                val parsed = store.save(text)
                text = store.text()
                message = when {
                    parsed.fellBackToDefault -> fallback
                    parsed.skipped > 0 -> skipped.format(parsed.skipped)
                    else -> saved
                }
            }, Modifier.fillMaxWidth().padding(top = 16.dp))
            MikaButton(stringResource(R.string.settings_price_compare_reset), {
                store.reset()
                text = store.text()
                message = saved
            }, Modifier.fillMaxWidth().padding(top = 8.dp), kind = ButtonKind.Tonal)
        }
    }
}
