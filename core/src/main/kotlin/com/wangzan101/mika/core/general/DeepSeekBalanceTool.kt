package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.tool.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object DeepSeekBalanceTool : AgentTool {
    override val verb = Verb.Diagnose
    override val risk = Risk.Read
    override val offScreen = true
    override val def = ToolDef("deepseek_balance", "Check the configured official DeepSeek API account balance when the user asks. This is a live read; it never exposes the API key. A network error means unknown, not zero. This does not report a third-party provider balance.", emptyMap(), emptyList())
    override fun available(ctx: ToolContext) = ctx.config.deepSeekBalance != null
    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = try {
        val result = ctx.config.deepSeekBalance!!.invoke()
        ok {
            put("configured", result.configured)
            result.available?.let { put("available", it) }
            put("balances", buildJsonObject { result.balances.forEach { (currency, amount) -> put(currency, amount) } })
        }
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: Exception) { fail("balance_unavailable", "Could not query the official DeepSeek balance; balance is unknown.", recoverable = true) }
}
