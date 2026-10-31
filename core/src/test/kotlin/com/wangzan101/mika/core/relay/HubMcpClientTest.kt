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
    private var expireSession = false

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.peek().readUtf8()
                seen += request
                if (request.path?.contains("/files") == true) return json("""{"file_id":"fl_rest","id":"fl_rest"}""")
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
                if (body.contains("diaodu_whoami")) {
                    return json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"agent_id\":\"ag_text\"}"}],"structuredContent":{"kind":"agent","agent_id":"ag_saved","user_id":"us_1"}}}""")
                }
                if (body.contains("diaodu_connect")) {
                    return json("""{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"{\"token\":\"da_once\",\"agent_id\":\"ag_text\"}"}],"structuredContent":{"agent_id":"ag_phone","user_id":"us_1","token":"da_once"}}}""")
                }
                if (body.contains("diaodu_relay_list")) {
                    val member = !body.contains("rl_gone")
                    return json("""{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{\"member\":true}"}],"structuredContent":{"sessions":${if (member) """[{"id":"ses_fixed","role":"worker"}]""" else "[]"},"member":$member}}}""")
                }
                if (body.contains("diaodu_relay_read")) {
                    return json("""{"jsonrpc":"2.0","id":4,"result":{"structuredContent":{"events":[{"seq":3,"kind":"message","from_owner":true,"sender":{"role":"owner","name":"o"},"message":{"kind":"instruction","text":"do"}},{"seq":4,"kind":"message","from_owner":false,"sender":{"role":"mentor","name":"m"},"message":{"kind":"instruction","text":"no"}}],"next_cursor":4,"has_more":false},"content":[{"type":"text","text":"{}"}]}}""")
                }
                if (body.contains("diaodu_relay_join")) {
                    return json("""{"jsonrpc":"2.0","id":4,"result":{"content":[{"type":"text","text":"{\"session\":{\"id\":\"ses_text\"}}"}],"structuredContent":{"session":{"id":"ses_structured","role":"worker"}}}}""")
                }
                if (body.contains("diaodu_relay_upload")) {
                    return json("""{"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"{\"file_id\":\"fl_9\"}"}]}}""")
                }
                if (body.contains("diaodu_relay_send")) {
                    if (expireSession) {
                        expireSession = false
                        return MockResponse().setResponseCode(404)
                    }
                    if (body.contains("\"request_id\":\"retry-me\"") && seen.count { it.body.peek().readUtf8().contains("diaodu_relay_send") } == 1) {
                        return MockResponse().setResponseCode(503)
                    }
                    if (body.contains("sse-me")) {
                        return MockResponse().setResponseCode(200)
                            .setHeader("content-type", "text/event-stream")
                            .setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":6,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"event\\\":{\\\"seq\\\":9}}\"}]}}\n\n")
                    }
                    if (body.contains("structured-seq")) {
                        return json("""{"jsonrpc":"2.0","id":8,"result":{"content":[{"type":"text","text":"{\"event\":{\"seq\":1}}"}],"structuredContent":{"event":{"seq":15,"kind":"message","from_owner":false,"sender":{"role":"worker","name":"p"}},"duplicate":false}}}""")
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
        assertEquals("ses_structured", client.join(mcp(), "da_test", "TOPIC1", env))
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
        val environment = joinArgs["environment"]!!.jsonObject
        assertEquals("Android", environment["os_name"]!!.jsonPrimitive.content)
        assertEquals("14 / API 34", environment["os_version"]!!.jsonPrimitive.content)
        assertEquals("arm64-v8a", environment["architecture"]!!.jsonPrimitive.content)
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

    @Test fun whoamiUsesBearerAndStructuredAgentId() {
        assertEquals("ag_saved", client.whoami(mcp(), "da_test"))
        val call = seen.first { it.body.peek().readUtf8().contains("diaodu_whoami") }
        assertEquals("Bearer da_test", call.getHeader("Authorization"))
        assertFalse(call.body.peek().readUtf8().contains("diaodu_connect"))
        assertFalse(seen.any { it.body.peek().readUtf8().contains("\"token\"") && it.body.peek().readUtf8().contains("diaodu_whoami") && it.body.peek().readUtf8().contains("da_once") })
    }

    @Test fun structuredSeqBeatsTextAndTextIsCapped() {
        assertEquals(15L, client.send(mcp(), "da_test", "ses_fixed", RelayKind.PROGRESS, "x", "structured-seq"))
        val long = "字".repeat(9000)
        client.send(mcp(), "da_test", "ses_fixed", RelayKind.PROGRESS, long, "capped")
        val text = arguments(seen.first { it.body.peek().readUtf8().contains("capped") })["text"]!!.jsonPrimitive.content
        assertTrue(text.length <= RELAY_MAX_TEXT)
    }

    @Test fun lostSessionReinitializesWithTheSameBearer() {
        expireSession = true
        assertEquals(15L, client.send(mcp(), "da_test", "ses_fixed", RelayKind.PROGRESS, "again", "after-restart"))
        val inits = seen.filter { it.body.peek().readUtf8().contains("\"method\":\"initialize\"") }
        assertEquals(2, inits.size)
        assertTrue(inits.all { it.getHeader("Authorization") == "Bearer da_test" })
        assertTrue(seen.none { it.body.peek().readUtf8().contains("diaodu_connect") })
    }

    @Test fun listSaysWhenTheStoredSessionIsGone() {
        assertFalse(client.memberOf(mcp(), "da_test", "rl_gone"))
        assertTrue(client.memberOf(mcp(), "da_test", "ses_fixed"))
        val gone = seen.first { it.body.peek().readUtf8().contains("rl_gone") }
        assertTrue(gone.body.peek().readUtf8().contains("diaodu_relay_list"))
    }

    @Test fun readCapsWaitAndParsesOwnerInstruction() {
        val page = client.read(mcp(), "da_test", "ses_fixed", after = 0, waitSeconds = 99)
        assertEquals(4L, page.nextCursor)
        assertEquals(listOf(3L), HubInstructionFilter.take(true, page).map { it.seq })
        val wait = arguments(seen.first { it.body.peek().readUtf8().contains("diaodu_relay_read") })["wait"]!!.jsonPrimitive.content
        assertEquals("20", wait)
    }

    @Test fun oversizedUploadUsesRelayRest() {
        val rest = HubMcpClient(mcpUploadMaxBytes = 3)
        assertEquals("fl_9", rest.upload(mcp(), "da_test", "ses_fixed", "small.png", byteArrayOf(1, 2)))
        assertEquals("fl_rest", rest.upload(mcp(), "da_test", "ses_fixed", "big.png", byteArrayOf(1, 2, 3, 4)))
        val raw = seen.first { it.path?.contains("/files") == true }
        assertTrue(raw.path!!.contains("/v1/relay/sessions/ses_fixed/files"))
        assertEquals("Bearer da_test", raw.getHeader("Authorization"))
        assertEquals(4, raw.body.peek().readByteArray().size)
        assertFalse(raw.body.peek().readUtf8().contains("content_base64"))
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
