package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.tool.CloudCommandResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudHostStatusTest {
    @Test fun configurationIsNotReportedAsAnActiveConnection() = runBlocking {
        var configured = false
        val dispatcher = toolDispatcher(FakeScreen(), cloudHostConfigured = { configured })
        suspend fun check() = dispatcher.execute(Part.ToolCall("check", "cloud_host_status", "{}")).content
        val first = check()
        assertEquals(first.toString(), "false", first["configured"]?.jsonPrimitive?.content)
        configured = true
        val result = check()
        assertEquals("true", result["configured"]!!.jsonPrimitive.content)
        assertEquals("not_checked", result["connection"]!!.jsonPrimitive.content)
        assertEquals("false", result["remote_execution_available"]!!.jsonPrimitive.content)
        assertEquals("false", result["cloud_scheduler_available"]!!.jsonPrimitive.content)
    }

    @Test fun commandReportsTimeoutAndExitWithoutClaimingSuccess() = runBlocking {
        var received = ""
        val dispatcher = toolDispatcher(FakeScreen(), cloudHostConfigured = { true }, cloudCommand = {
            received = it
            CloudCommandResult("partial output", null, timedOut = true, truncated = false)
        })
        val result = dispatcher.execute(Part.ToolCall("cmd", "cloud_ssh_command", "{\"command\":\"hostname\"}")).content
        assertEquals("hostname", received)
        assertEquals("true", result["timed_out"]?.jsonPrimitive?.content)
        assertEquals(null, result["exit_code"])
        assertEquals("true", dispatcher.execute(Part.ToolCall("status", "cloud_host_status", "{}")).content["remote_execution_available"]?.jsonPrimitive?.content)
    }
}
