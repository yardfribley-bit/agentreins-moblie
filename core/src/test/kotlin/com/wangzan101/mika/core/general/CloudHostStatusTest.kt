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
import java.nio.file.Files

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

    @Test fun backgroundJobSurvivesTheSubmittingShellAndReportsExit() {
        val home = Files.createTempDirectory("cloud-job-test").toFile()
        val id = "12345678-1234-1234-1234-123456789abc"
        fun shell(script: String): Pair<Int, String> {
            val p = ProcessBuilder("sh", "-c", script).apply { environment()["HOME"] = home.absolutePath }.redirectErrorStream(true).start()
            val output = p.inputStream.bufferedReader().readText()
            return p.waitFor() to output
        }
        try {
            val started = shell(CloudBackgroundJob.startCommand(id, "sleep 0.2; printf '中文 result'; exit 7"))
            assertEquals(started.toString(), 0, started.first)
            var status = ""
            repeat(50) {
                status = shell(CloudBackgroundJob.statusCommand(id)).second
                if ("state=finished" in status) return@repeat
                Thread.sleep(50)
            }
            org.junit.Assert.assertTrue(status, "state=finished" in status)
            org.junit.Assert.assertTrue(status, "exit_code=7" in status)
            org.junit.Assert.assertTrue(status, "中文 result" in status)
            org.junit.Assert.assertTrue(shell(CloudBackgroundJob.startCommand(id, "echo duplicate")).first != 0)
        } finally { home.deleteRecursively() }
    }

    @Test fun unknownSubmissionReturnsItsIdForRecovery() = runBlocking {
        val dispatcher = toolDispatcher(FakeScreen(), cloudHostConfigured = { true }, cloudCommand = { throw IllegalStateException("disconnected") })
        val result = dispatcher.execute(Part.ToolCall("job", "cloud_background_job", """{"operation":"start","command":"echo test"}""")).content
        assertEquals("unknown", result["outcome"]?.jsonPrimitive?.content)
        org.junit.Assert.assertTrue(result["job_id"]!!.jsonPrimitive.content.matches(Regex("[0-9a-f-]{36}")))
    }
}
