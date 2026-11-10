package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.compare.PriceCompareAnswer
import com.wangzan101.mika.core.compare.PriceRow
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 比价回答：有记下的行就画成对比，没有就照原来的文字显示。 */
@Composable
fun PriceCompareAnswerText(answer: String, style: TextStyle) {
    val view = remember(answer) { PriceCompareAnswer.parse(answer) }
    if (view.rows.isEmpty()) {
        MarkdownText(answer, style)
        return
    }
    if (view.prose.isNotBlank()) MarkdownText(view.prose, style)
    MikaCard(Modifier.fillMaxWidth().padding(top = if (view.prose.isNotBlank()) 12.dp else 0.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.compare_title), style = MikaType.BodyStrong, color = MikaTheme.colors.ink)
            view.rows.forEach { PriceCompareRow(it) }
        }
    }
}

@Composable
private fun PriceCompareRow(row: PriceRow) {
    val c = MikaTheme.colors
    val product = listOf(row.title, row.spec).filter { it.isNotBlank() }.joinToString(" · ")
        .ifBlank { stringResource(R.string.compare_product_missing) }
    val prices = listOfNotNull(
        row.listPrice?.let { stringResource(R.string.compare_list_price, it) },
        when (row.payKind) {
            "coupon" -> row.payPrice?.let { stringResource(R.string.compare_coupon_price, it) }
            "shown" -> row.payPrice?.let { stringResource(R.string.compare_shown_price, it) }
            else -> null
        },
    ).joinToString(" · ")
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(row.platform, style = MikaType.BodyStrong, color = c.ink)
        Text(product, Modifier.padding(top = 2.dp), style = MikaType.Body, color = c.ink2)
        if (prices.isNotEmpty()) {
            Text(prices, Modifier.padding(top = 2.dp), style = MikaType.Body, fontWeight = FontWeight.SemiBold, color = c.ink)
        }
        if (row.status != "ok" || prices.isEmpty()) {
            val reason = statusLabel(row.status) + if (row.note.isBlank()) "" else " · ${row.note}"
            Text(stringResource(R.string.compare_reason, reason), Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink2)
        }
        if (row.shop.isNotBlank()) {
            Text(stringResource(R.string.compare_shop, row.shop), Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
        }
        val evidence = if (row.evidence.isBlank()) stringResource(R.string.compare_no_evidence) else stringResource(R.string.compare_evidence, row.evidence)
        Text(evidence, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "ok" -> stringResource(R.string.compare_status_ok)
    "not_installed" -> stringResource(R.string.compare_status_not_installed)
    "needs_login" -> stringResource(R.string.compare_status_needs_login)
    "out_of_stock" -> stringResource(R.string.compare_status_out_of_stock)
    "left_virtual_display" -> stringResource(R.string.compare_status_left_virtual_display)
    "not_found" -> stringResource(R.string.compare_status_not_found)
    "unreadable" -> stringResource(R.string.compare_status_unreadable)
    else -> status
}
