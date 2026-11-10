package com.wangzan101.mika.core.compare

/** 比价进行中不许点的按钮。只认控件上的字，坐标点到没名字的位置这里拦不住。 */
object PriceCompareGuard {
    private val phrases = listOf(
        "加入购物车", "加购", "立即购买", "马上抢", "领券", "领取优惠", "去结算", "提交订单",
        "立即支付", "确认支付", "请登录", "立即登录", "去登录", "一键登录", "手机号登录", "账号登录", "微信登录", "密码登录",
    )

    /** 这个控件文字是购物或登录动作时，返回命中的词；否则 null。 */
    fun blockedTap(label: String?): String? {
        val text = label?.trim().orEmpty()
        if (text.isEmpty()) return null
        phrases.firstOrNull { it in text }?.let { return it }
        if (text.length <= 6 && "登录" in text) return "登录"
        return null
    }
}
