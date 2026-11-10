package com.wangzan101.mika.core.compare

import kotlinx.serialization.Serializable

/** 一家记下来的结果。界面按这些字段画对比，不采用模型自己写的价格。 */
@Serializable
data class PriceRow(
    val platform: String,
    val packageName: String,
    val title: String = "",
    val spec: String = "",
    val shop: String = "",
    val status: String,
    val listPrice: String? = null,
    val payPrice: String? = null,
    val payKind: String = "none",
    val evidence: String = "",
    val quoteOnScreen: Boolean = false,
    val screenPackage: String = "",
    /** 没有数字的短说明。带数字的说明不收，避免在原因里夹一个价。 */
    val note: String = "",
)

/** 这一次任务记下的各家。同一包名再记一次就覆盖，顺序保持第一次出现的位置。 */
class PriceCompareLedger {
    var armed: Boolean = false
        private set

    private val rows = mutableListOf<PriceRow>()

    fun arm() { armed = true }

    fun upsert(row: PriceRow) {
        val i = rows.indexOfFirst { it.packageName == row.packageName }
        if (i >= 0) rows[i] = row else rows += row
    }

    fun snapshot(): List<PriceRow> = rows.toList()
}
