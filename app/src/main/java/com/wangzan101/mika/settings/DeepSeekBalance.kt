package com.wangzan101.mika.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

data class BalanceEntry(val currency: String, val total: BigDecimal)
data class BalanceSnapshot(val available: Boolean, val entries: List<BalanceEntry>)

/** Only call the official endpoint with keys belonging to the official provider. */
object DeepSeekBalance {
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).followRedirects(false).build()
    fun isOfficial(provider: Provider): Boolean = runCatching {
        val url = java.net.URI(provider.baseUrl)
        url.scheme.equals("https", true) && url.host.equals("api.deepseek.com", true) && (url.port == -1 || url.port == 443) && url.userInfo == null
    }.getOrDefault(false)

    fun fetch(key: String): BalanceSnapshot {
        require(key.isNotBlank())
        val request = Request.Builder().url("https://api.deepseek.com/user/balance").header("Authorization", "Bearer $key").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("http_${response.code}")
            return parse(response.body?.string() ?: error("empty_response"))
        }
    }

    internal fun parse(body: String): BalanceSnapshot {
            val root = Json.parseToJsonElement(body).jsonObject
            val available = root["is_available"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: error("invalid_response")
            val entries = root["balance_infos"]?.jsonArray?.map {
                val item = it.jsonObject
                val currency = item.getValue("currency").jsonPrimitive.content
                val total = item.getValue("total_balance").jsonPrimitive.content.toBigDecimal()
                BalanceEntry(currency, total)
            } ?: error("invalid_response")
            require(entries.isNotEmpty())
            return BalanceSnapshot(available, entries)
    }

    fun isLow(snapshot: BalanceSnapshot): Boolean = !snapshot.available || snapshot.entries.any {
        when (it.currency) { "CNY" -> it.total < BigDecimal("5"); "USD" -> it.total < BigDecimal("1"); else -> false }
    }
}
