package com.wangzan101.mika.core.relay

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HubMcpClientTest {
    private val server = MockWebServer()
    private val client = HubMcpClient()
    private val env = RelayEnvironment("Android", "14 / API 34", "arm64-v8a")
    private val seen = mutableListOf<RecordedRequest>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.peek().readUtf8()
                seen += request
                val method = request.method
                if (method != "POST") return MockResponse().setResponseCode(405)
                if (body.contains("\"method\":\"initialize\"") || body.contains("\"method\": \"initialize\"")) {
                    return json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{"tools":{}},"serverInfo":{"name":"diaodu","version":"2026.10.28"}}}""")
                        .addHeader("mcp-session-id", "mcp_sess")
                }
                if (body.contains("notifications/initialized")) return MockResponse().setResponseCode(202)
                if (body.contains("\"method\":\"tools/list\"") || body.contains("\"method\": \"tools/list\"")) {
                    return json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"diaodu_connect"},{"name":"diaodu_relay_send"},{"name":"diaodu_relay_join"}]}}""")
                }
                if (body.contains("diaodu_connect")) {
                    return json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"token\":\"da_once\",\"agent_id\":\"ag_phone\"}"}]}}""")
                }
                if (body.contains("diaodu_relay_join")) {
                    return json("""{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{\"session\":{\"id\":\"ses_fixed\"}}"}]}}""")
                }
                if (body.contains("diaodu_relay_upload")) {
                    return json("""{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"{\"file_id\":\"fl_9\"}"}]}}""")
                }
                if (body.contains("diaodu_relay_send")) {
                    if (body.contains("\"request_id\":\"retry-me\"") && seen.count { it.body.peek().readUtf8().contains("diaodu_relay_send") } == 1) {
                        return MockResponse().setResponseCode(503)
                    }
                    if (body.contains("sse-me")) {
                        return MockResponse().setResponseCode(200)
                            .setHeader("content-type", "text/event-stream")
                            .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"event\\\":{\\\"seq\\\":9}}\"}]}}\n\n")
                    }
                    if (body.contains("bad-token")) {
                        return json("""{"jsonrpc":"2.0","id":7,"result":{"isError":true,"content":[{"type":"text","text":"{\"code\":401,\"message\":\"rejected da_test and sk-abcdefghijklmnopqrstuvwxyz\"}"}]}}""")
                    }
                    return json("""{"jsonrpc":"2.0","id":8,"result":{"content":[{"type":"text","text":"{\"event\":{\"seq\":15}}"}]}}""")
                }
                if (body.contains("diaodu_relay_create")) return MockResponse().setResponseCode(500)
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown() }

    private fun mcp(): String = server.url("/task").toString().trimEnd('/')

    @Test fun handshakeRegistersAndListsTools() {
        val identity = client.connect(mcp(), "phone", "agentreins-moblie")
        assertEquals("da_once", identity.token)
        assertEquals("ag_phone", identity.agentId)
        val names = client.listTools(mcp(), identity.token)
        assertEquals(listOf("diaodu_connect", "diaodu_relay_send", "diaodu_relay_join"), names)

        val init = seen.first { it.body.peek().readUtf8().contains("initialize") }
        assertEquals("/task/v1/mcp", init.path)
        assertEquals("mcp_sess", seen.first { it.body.peek().readUtf8().contains("diaodu_connect") }.getHeader("mcp-session-id"))
        assertTrue(seen.any { it.body.peek().readUtf8().contains("notifications/initialized") })
        assertEquals("2025-03-26", init.getHeader("MCP-Protocol-Version"))
        assertTrue(seen.none { it.path!!.contains("/v1/relay") })
        assertTrue(seen.none { it.body.peek().readUtf8().contains("diaodu_relay_create") })
    }

    @Test fun joinAndSendUseRelayToolsNotRest() {
        assertEquals("ses_fixed", client.join(mcp(), "da_test", "TOPIC1", env))
        val seq = client.send(mcp(), "da_test", "ses_fixed", RelayKind.PROGRESS, "第 1 步 phone_observe", "run:1")
        assertEquals(15L, seq)
        val again = client.send(mcp(), "da_test", "ses_fixed", RelayKind.PROGRESS, "第 1 步 phone_observe", "run:1")
        assertEquals(15L, again)
        val sends = seen.filter { it.body.peek().readUtf8().contains("diaodu_relay_send") }
        assertEquals(2, sends.size)
        assertEquals(arguments(sends[0]), arguments(sends[1]))
        val posted = kotlinx.serialization.json.Json.parseToJsonElement(sends[0].body.peek().readUtf8()).jsonObject
        val args = posted["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("progress", args["kind"]!!.jsonPrimitive.content)
        assertEquals("run:1", args["request_id"]!!.jsonPrimitive.content)
        assertEquals("ses_fixed", args["session"]!!.jsonPrimitive.content)
        assertEquals("Bearer da_test", sends[0].getHeader("Authorization"))
        assertFalse(sends[0].path!!.contains("da_test"))
        val join = seen.first { it.body.peek().readUtf8().contains("diaodu_relay_join") }
        val joinArgs = kotlinx.serialization.json.Json.parseToJsonElement(join.body.peek().readUtf8()).jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("worker", joinArgs["role"]!!.jsonPrimitive.content)
        assertEquals("Android", joinArgs["environment"]!!.jsonObject["os_name"]!!.jsonPrimitive.content)
        assertTrue(seen.none { it.path!!.contains("/v1/relay") })
    }

    @Test fun httpFailureRetriesWithTheSameRequestId() {
        assertThrows(RelayException::class.java) {
            client.send(mcp(), "da_test", "ses_fixed", RelayKind.RESULT, "结束", "retry-me")
        }
        assertEquals(15L, client.send(mcp(), "da_test", "ses_fixed", RelayKind.RESULT, "结束", "retry-me"))
        val sends = seen.filter { it.body.peek().readUtf8().contains("retry-me") }
        assertEquals(2, sends.size)
        assertEquals(arguments(sends[0]), arguments(sends[1]))
        assertEquals("result", arguments(sends[0])["kind"]!!.jsonPrimitive.content)
    }

    @Test fun sseResultAndScreenshotUpload() {
        val file = client.upload(mcp(), "da_test", "ses_fixed", "screen-0003.png", byteArrayOf(1, 2, 3, 4))
        assertEquals("fl_9", file)
        assertEquals(9L, client.send(mcp(), "da_test", "ses_fixed", RelayKind.RESULT, "结束", "sse-me", listOf(file)))
        val upload = seen.first { it.body.peek().readUtf8().contains("diaodu_relay_upload") }
        assertTrue(upload.body.peek().readUtf8().contains("content_base64"))
        assertFalse(upload.body.peek().readUtf8().contains("da_test"))
    }

    @Test fun toolErrorDoesNotLeakToken() {
        val error = assertThrows(RelayException::class.java) {
            client.send(mcp(), "da_test", "ses_fixed", RelayKind.PROGRESS, "hi", "bad-token")
        }
        assertEquals(401, error.status)
        assertFalse(error.retryable)
        assertFalse(error.message!!.contains("da_test"))
        assertFalse(error.message!!.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(error.message!!.contains("***"))
    }

    @Test fun workerRejectsInstructionWithoutCallingHub() {
        assertThrows(RelayException::class.java) {
            client.send(mcp(), "da_test", "ses_fixed", "instruction", "do it", "run:1")
        }
        assertEquals(0, seen.size)
    }

    @Test fun siteRootBecomesMcpPath() {
        assertEquals("https://www.chuhaijian.com/task/v1/mcp", mcpEndpoint("https://www.chuhaijian.com/task"))
        assertEquals("https://www.chuhaijian.com/task/v1/mcp", mcpEndpoint("https://www.chuhaijian.com/task/v1/mcp/"))
        assertEquals(server.url("/v1/mcp").toString().trimEnd('/'), mcpEndpoint(server.url("/").toString()))
    }

    private fun arguments(request: RecordedRequest) =
        kotlinx.serialization.json.Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject

    private fun json(body: String) = MockResponse().setHeader("content-type", "application/json").setBody(body)
}
