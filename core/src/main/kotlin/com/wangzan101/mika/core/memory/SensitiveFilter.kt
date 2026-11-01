package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.record.Redactor
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/**
 * 记忆与技能草稿的敏感信息关卡:命中就不存。
 * 手机号、地址、生日、家人称呼不在此列——它们是办事常用的事实。
 */
object SensitiveFilter {
    private const val KEYWORDS =
        "支付密码|密码|口令|验证码|校验码|动态码|安全码|安全问题|" +
            "(?<![A-Za-z])(?:password|passcode|passwd|cvv|cvc|verification code|security code|security question|one-time code)(?![A-Za-z])"
    // 这两个词太短,常见于普通英文(pinned、hotpot),只在后面跟数字时才算
    private const val DIGIT_KEYWORDS = "(?<![A-Za-z])(?:pin|otp)(?![A-Za-z])"
    private const val GAP = "[\\s:：=,，是为叫]{0,4}(?:(?:is|was|are)\\s+)?"
    private const val OWNER = "\\s*(?:就是|是|为)(?:我的|我|用户的)?\\s*"

    // 关键词后面要像密钥:含数字,或恢复码本身。纯字母(Gmail、login、GitHub)是账号标识,不是密钥。
    private const val SECRET_VALUE =
        "(?:[0-9]{4,}|[A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*[0-9][A-Za-z0-9]*|[A-Za-z0-9]*[0-9][A-Za-z0-9]*[A-Za-z][A-Za-z0-9]*)"
    private const val ANY_TOKEN = "[A-Za-z0-9][A-Za-z0-9\\-]{3,}"
    private const val RECOVERY = "恢复码|备用码|(?<![A-Za-z])recovery code(?![A-Za-z])"
    private val SECRET_VALUE_RE = Regex(SECRET_VALUE)
    private val ANY_TOKEN_RE = Regex(ANY_TOKEN, RegexOption.IGNORE_CASE)
    private val RULES = listOf(
        Regex("(?:$KEYWORDS)$GAP$SECRET_VALUE", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])$SECRET_VALUE$OWNER(?:$KEYWORDS)", RegexOption.IGNORE_CASE),
        Regex("$DIGIT_KEYWORDS$GAP[0-9]{4,}", RegexOption.IGNORE_CASE),
        Regex("(?<![A-Za-z0-9])[0-9]{4,}$OWNER$DIGIT_KEYWORDS", RegexOption.IGNORE_CASE),
        Regex("(?:$RECOVERY)$GAP$ANY_TOKEN", RegexOption.IGNORE_CASE),
    )
    /** 16 位以上、带数字的 base32，当作 TOTP 密钥。邮箱和平台名对不上。 */
    private val TOTP = Regex("""(?i)(?<![A-Za-z0-9])[a-z2-7]{16,}(?![A-Za-z0-9])""")
    private val ID_NUMBER = Regex("(?<![0-9])[1-9][0-9]{16}[0-9Xx](?![0-9A-Za-z])")
    // 数字之间允许单个空格或连字符(卡号常分组书写)
    private val CARD_LIKE = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){12,18}(?![0-9])")
    private val SPACES = Regex("\\s+")
    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private const val ID_CHECK = "10X98765432"

    /** 命中的类别名(`api_key`|`id_number`|`card_number`|`password`);未命中 null。 */
    fun hit(text: String): String? {
        // 全角数字字母、兼容字符先统一;关键词规则再对去掉空白的文本查一遍(「验证码 1 2 3 4」)
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val compact = SPACES.replace(t, "")
        return when {
            Redactor.text(t) != t -> "api_key"
            ID_NUMBER.findAll(t).any { validId(it.value) } -> "id_number"
            CARD_LIKE.findAll(t).any { luhn(it.value.filter(Char::isDigit)) } -> "card_number"
            totp(t) || totp(compact) -> "totp"
            RULES.any { it.containsMatchIn(t) || it.containsMatchIn(compact) } -> "password"
            else -> null
        }
    }

    /** 去掉密钥值，留下账号说法。去不干净就返回 null，调用方不要把原文存下来。 */
    fun redact(text: String): String? {
        val t = Normalizer.normalize(text, Normalizer.Form.NFKC)
        var out = t
        out = TOTP.replace(out) { if (it.value.any(Char::isDigit)) "***" else it.value }
        for (rule in RULES) out = rule.replace(out) { m ->
            val value = SECRET_VALUE_RE.find(m.value) ?: ANY_TOKEN_RE.find(m.value)
            if (value == null) "***" else m.value.replaceRange(value.range.first, value.range.last + 1, "***")
        }
        return out.takeIf { hit(it) == null }
    }

    private fun totp(text: String) = TOTP.findAll(text).any { it.value.any(Char::isDigit) }

    /** 出生日期段是真实日期,末位符合 ISO 7064 MOD 11-2。 */
    private fun validId(id: String): Boolean {
        val birth = try {
            LocalDate.of(id.substring(6, 10).toInt(), id.substring(10, 12).toInt(), id.substring(12, 14).toInt())
        } catch (e: DateTimeException) {
            return false
        }
        if (birth.year < 1900) return false
        val sum = (0 until 17).sumOf { (id[it] - '0') * ID_WEIGHTS[it] }
        return ID_CHECK[sum % 11] == id[17].uppercaseChar()
    }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        var sum = 0
        digits.reversed().forEachIndexed { i, c ->
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }
}
