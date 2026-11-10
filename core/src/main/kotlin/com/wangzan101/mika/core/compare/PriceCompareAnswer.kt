package com.wangzan101.mika.core.compare

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 用户那句是不是在要比价。用来决定回答里能不能留下模型自己写的价格。 */
object PriceCompareIntent {
    private val cues = listOf("比价", "比一下", "比一比", "比较价格", "compare price", "compare prices")

    fun matches(text: String): Boolean = cues.any { text.contains(it, ignoreCase = true) }
}

data class PriceCompareView(val prose: String, val rows: List<PriceRow>)

/**
 * 比价任务交给用户的回答。有记下的行时，回答只含这些行，模型另写的价格不会出现。
 * 一行都没有时，去掉回答里的价格写法，解释可以留下。
 */
object PriceCompareAnswer {
    const val START = "<<<PRICE_COMPARE>>>"
    const val END = "<<<END_PRICE_COMPARE>>>"

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun present(task: String, answer: String, rows: List<PriceRow>): String {
        if (!PriceCompareIntent.matches(task) && rows.isEmpty()) return answer
        if (rows.isEmpty()) return stripPrices(answer)
        return START + "\n" + json.encodeToString(ListSerializer(PriceRow.serializer()), rows) + "\n" + END
    }

    fun parse(answer: String): PriceCompareView {
        val start = answer.indexOf(START)
        val end = answer.indexOf(END)
        if (start < 0 || end <= start) return PriceCompareView(answer.trim(), emptyList())
        val prose = answer.substring(0, start).trim()
        val body = answer.substring(start + START.length, end).trim()
        val rows = runCatching { json.decodeFromString(ListSerializer(PriceRow.serializer()), body) }.getOrDefault(emptyList())
        return PriceCompareView(prose, rows)
    }

    /** 去掉货币金额和「券后 12.8」这种写法。普通数字（规格、数量）留着。 */
    fun stripPrices(text: String): String {
        var s = text
        s = s.replace(Regex("""[¥￥]\s*\d+(?:\.\d{1,2})?"""), "")
        s = s.replace(Regex("""\d+(?:\.\d{1,2})?\s*元"""), "")
        s = s.replace(Regex("""(?:券后|到手|实付|原价|标价|日常价)\s*\d+(?:\.\d{1,2})?"""), "")
        return s.replace(Regex("""[ \t]{2,}"""), " ").trim()
    }
}
