package com.wangzan101.mika.core.vault

import com.wangzan101.mika.core.record.Redactor
import java.text.Normalizer

/**
 * 写入库之前遮住秘密。支付密码的数字一律不留下。
 * 普通金额、日期、没有「手机号」这类词的数字不动。
 */
object VaultRedactor {
    private val PIN_WORD = Regex("""支付密码|付款密码|交易密码|支付口令|取款密码|银行卡密码|(?i:payment\s*pin)""")
    private val PIN_DIGITS = Regex("""(?<![0-9])[0-9](?:[ \-]?[0-9]){3,7}(?![0-9])""")
    private val SECRET = Regex(
        """(?i)(密码|口令|验证码|校验码|动态码|动态密码|安全码|邀请码|加入码|join code|one-time code|otp|passcode|password|passwd|api[-_ ]?key|access[-_ ]?key|secret|token)""" +
            """(\s*[:：=是为]\s*|\s+)([A-Za-z0-9_\-+/=]{4,}|[0-9](?:[ \-]?[0-9]){3,17})""",
    )
    private val PHONE = Regex(
        """(手机号|手机号码|电话号码|联系电话|绑定的?手机|(?i:phone\s*(?:number)?))([^0-9+]{0,8})((?:\+?86[-\s]?)?1[3-9](?:[ \-]?\d){9})""",
    )
    private val ID_NUMBER = Regex("""(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])""")

    fun redact(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        var out = maskPay(nfkc)
        out = SECRET.replace(out) { it.groupValues[1] + it.groupValues[2] + Redactor.MASK }
        out = PHONE.replace(out) { it.groupValues[1] + it.groupValues[2] + Redactor.MASK }
        out = ID_NUMBER.replace(out, Redactor.MASK)
        return Redactor.text(out)
    }

    /** 支付密码前后很近的 4–8 位数字遮住;后面紧跟「年」「元」的是日期或金额,留下。 */
    private fun maskPay(text: String): String {
        val words = PIN_WORD.findAll(text).map { it.range }.toList()
        if (words.isEmpty()) return text
        return PIN_DIGITS.replace(text) { m ->
            val next = text.getOrNull(m.range.last + 1)
            if (next == '年' || next == '元' || next == '点' || next == '月' || next == '日') return@replace m.value
            val near = words.any { w ->
                m.range.first - w.last in 1..12 || w.first - m.range.last in 1..6
            }
            if (near) Redactor.MASK else m.value
        }
    }
}
