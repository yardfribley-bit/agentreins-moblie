package com.wangzan101.mika.core.compare

/** 一家的结果。价格只从屏幕原文里提取，不接受模型另报的数字。 */
object PriceCompareStatus {
    const val OK = "ok"
    const val NOT_INSTALLED = "not_installed"
    const val NEEDS_LOGIN = "needs_login"
    const val OUT_OF_STOCK = "out_of_stock"
    const val LEFT_VIRTUAL_DISPLAY = "left_virtual_display"
    const val NOT_FOUND = "not_found"
    const val UNREADABLE = "unreadable"
    val ALL = setOf(OK, NOT_INSTALLED, NEEDS_LOGIN, OUT_OF_STOCK, LEFT_VIRTUAL_DISPLAY, NOT_FOUND, UNREADABLE)
}

data class FoundMoney(val amount: String, val kind: String)

data class PriceDecision(
    /** 这次能不能记下来。报了价格但原文对不上屏幕时不记。 */
    val store: Boolean,
    val status: String,
    val listPrice: String?,
    val payPrice: String?,
    /** coupon、shown 或 none。 */
    val payKind: String,
    /** 对得上屏幕的原文摘录；对不上则为空，不拿来当依据。 */
    val evidence: String,
    val quoteOnScreen: Boolean,
    /** 不记下来时的原因；记下来时为 null。 */
    val reject: String?,
)

object PriceGrounding {
    private val MONEY = Regex("""(?:¥|￥)\s*(\d{1,6}(?:\.\d{1,2})?)|(\d{1,6}(?:\.\d{1,2})?)\s*元""")
    private val BARE = Regex("""(?<!\d)(\d{1,6}(?:\.\d{1,2})?)(?!\d)""")
    private val COUPON = listOf("券后", "到手", "实付", "补贴")
    private val LIST = listOf("原价", "标价", "日常价", "划线")
    private val ANY = COUPON + LIST + listOf("售价", "价格", "单价")

    fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    /** [quote] 去掉空白后是 [screen] 的一段。太短的摘录不算。 */
    fun onScreen(quote: String, screen: String): Boolean {
        val q = compact(quote)
        if (q.length < 2) return false
        return compact(screen).contains(q)
    }

    /** 只认带货币符号、带「元」，或紧挨着价格词的数字。 */
    fun extract(quote: String): List<FoundMoney> {
        val found = mutableListOf<FoundMoney>()
        val used = mutableListOf<IntRange>()
        fun take(range: IntRange, amount: String) {
            if (used.any { it.first <= range.last && range.first <= it.last }) return
            used += range
            found += FoundMoney(amount, kindAt(quote, range.first))
        }
        for (m in MONEY.findAll(quote)) {
            val amount = m.groupValues.drop(1).first { it.isNotEmpty() }
            take(m.range, amount)
        }
        for (m in BARE.findAll(quote)) {
            val start = m.range.first
            val window = quote.substring((start - 8).coerceAtLeast(0), start)
            if (ANY.none { it in window }) continue
            take(m.range, m.groupValues[1])
        }
        return found
    }

    /**
     * [status] 是模型看到的情况。[quote] 里的价格只有整段都出现在 [screen] 上才留下。
     * [screen] 为空时不留任何价格。报 ok 却对不上屏幕时整条拒绝，让模型拿原文再记一次。
     */
    fun decide(status: String, quote: String, screen: String?): PriceDecision {
        val onScreen = !screen.isNullOrBlank() && quote.isNotBlank() && onScreen(quote, screen)
        val prices = if (onScreen) extract(quote) else emptyList()
        val list = prices.firstOrNull { it.kind == "list" }?.amount
        val coupon = prices.firstOrNull { it.kind == "coupon" }?.amount
        val shown = prices.firstOrNull { it.kind == "shown" }?.amount
        val pay = coupon ?: shown
        val payKind = when {
            coupon != null -> "coupon"
            pay != null -> "shown"
            else -> "none"
        }
        if (status == PriceCompareStatus.OK && !onScreen) {
            return PriceDecision(false, status, null, null, "none", "", false, "quote_not_on_screen")
        }
        val keptStatus = if (status == PriceCompareStatus.OK && list == null && pay == null) PriceCompareStatus.UNREADABLE else status
        val keptKind = if (keptStatus == PriceCompareStatus.UNREADABLE && pay == null) "none" else payKind
        return PriceDecision(
            store = true,
            status = keptStatus,
            listPrice = list,
            payPrice = if (keptKind == "none") null else pay,
            payKind = keptKind,
            evidence = if (onScreen) quote.trim().take(180) else "",
            quoteOnScreen = onScreen,
            reject = null,
        )
    }

    /** 商品名、规格、店铺必须是这一屏里的原文，不能另写。对不上屏幕就丢掉。 */
    fun groundedField(field: String, screen: String): String {
        val text = field.trim().take(80)
        if (text.isEmpty() || screen.isBlank() || !onScreen(text, screen)) return ""
        return text
    }

    private fun kindAt(quote: String, index: Int): String {
        val window = quote.substring((index - 8).coerceAtLeast(0), index)
        return when {
            COUPON.any { it in window } -> "coupon"
            LIST.any { it in window } -> "list"
            else -> "shown"
        }
    }
}
