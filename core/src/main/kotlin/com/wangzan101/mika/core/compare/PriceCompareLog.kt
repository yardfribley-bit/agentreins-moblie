package com.wangzan101.mika.core.compare

/** 真机一次跑完就能从 logcat 过滤 PRICE_COMPARE 看出各家卡在哪。 */
object PriceCompareLog {
    fun armed() = "PRICE_COMPARE_ARMED"

    fun blocked(word: String) = "PRICE_COMPARE_BLOCKED word=${token(word)}"

    fun row(row: PriceRow): String = buildString {
        append("PRICE_COMPARE")
        append(" platform=").append(token(row.platform))
        append(" package=").append(token(row.packageName))
        append(" status=").append(row.status)
        append(" quote_on_screen=").append(row.quoteOnScreen)
        append(" screen_pkg=").append(token(row.screenPackage))
        append(" list=").append(row.listPrice.orEmpty())
        append(" pay_kind=").append(row.payKind)
        append(" pay=").append(row.payPrice.orEmpty())
        append(" quote_chars=").append(row.evidence.length)
    }

    fun summary(rows: List<PriceRow>): String {
        fun n(status: String) = rows.count { it.status == status }
        val coupon = rows.count { it.payKind == "coupon" }
        val shown = rows.count { it.payKind == "shown" }
        return "PRICE_COMPARE_SUMMARY n=${rows.size}" +
            " ok=${n(PriceCompareStatus.OK)}" +
            " needs_login=${n(PriceCompareStatus.NEEDS_LOGIN)}" +
            " left_virtual_display=${n(PriceCompareStatus.LEFT_VIRTUAL_DISPLAY)}" +
            " not_installed=${n(PriceCompareStatus.NOT_INSTALLED)}" +
            " out_of_stock=${n(PriceCompareStatus.OUT_OF_STOCK)}" +
            " not_found=${n(PriceCompareStatus.NOT_FOUND)}" +
            " unreadable=${n(PriceCompareStatus.UNREADABLE)}" +
            " coupon=$coupon shown=$shown"
    }

    private fun token(text: String) = text.replace(Regex("\\s+"), "_").take(40)
}
