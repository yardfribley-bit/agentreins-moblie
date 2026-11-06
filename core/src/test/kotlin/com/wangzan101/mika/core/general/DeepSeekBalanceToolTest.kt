package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.DeepSeekBalanceResult
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekBalanceToolTest {
    @Test fun balanceToolReportsOnlyLiveResultAndFailureIsUnknown() = runBlocking {
        var fails = false
        val dispatcher = toolDispatcher(FakeScreen(), deepSeekBalance = {
            if (fails) error("network")
            DeepSeekBalanceResult(true, true, mapOf("CNY" to "12.34"))
        })
        val call = Part.ToolCall("balance", "deepseek_balance", "{}")
        val result = dispatcher.execute(call).content
        assertEquals("12.34", result["balances"]!!.jsonObject["CNY"]!!.jsonPrimitive.content)
        fails = true
        val failure = dispatcher.execute(call).content
        assertEquals("balance_unavailable", failure["error"]!!.jsonPrimitive.content)
        assertEquals(null, failure["balances"])
    }
}
