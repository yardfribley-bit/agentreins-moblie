package com.wangzan101.mika.core.relay

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HubRelayClientTest {
    private val server = MockWebServer()
    private val client = HubRelayClient()
    private val env = RelayEnvironment("Android", "14 / API 34", "arm64-v8a")

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun base(): String = server.url("/task").toString().trimEnd('/')

    @Test fun joinIsWorkerAndDoesNotCreateSession() {
        server.enqueue(MockResponse().setBody("""{"token":"da_once"}"""))
        server.enqueue(MockResponse().setBody("""{"session":{"id":"ses_fixed","status":"active"}}"""))
        assertEquals("da_once", client.connect(base(), "phone", "agentreins-moblie"))
        assertEquals("ses_fixed", client.join(base(), "da_once", "TOPIC1", env))

        val connect = server.takeRequest()
        val join = server.takeRequest()
        assertEquals("/task/v1/connect", connect.path)
        assertEquals("POST", connect.method)
        assertEquals(null, connect.getHeader("Authorization"))
        assertEquals("/task/v1/relay/join", join.path)
        assertEquals("Bearer da_once", join.getHeader("Authorization"))
        val body = kotlinx.serialization.json.Json.parseToJsonElement(join.body.readUtf8()).jsonObject
        assertEquals("TOPIC1", body["code"]!!.jsonPrimitive.content)
        assertEquals("worker", body["role"]!!.jsonPrimitive.content)
        val environment = body["environment"]!!.jsonObject
        assertEquals("Android", environment["os_name"]!!.jsonPrimitive.content)
        assertEquals("14 / API 34", environment["os_version"]!!.jsonPrimitive.content)
        assertEquals("arm64-v8a", environment["architecture"]!!.jsonPrimitive.content)
        assertEquals(2, server.requestCount)
        assertFalse(connect.path!!.substringBefore('?').trimEnd('/').endsWith("/v1/relay/sessions"))
        assertFalse(join.path!!.substringBefore('?').trimEnd('/').endsWith("/v1/relay/sessions"))
    }

    @Test fun progressUsesSameRequestIdAndBodyOnReplay() {
        server.enqueue(MockResponse().setBody("""{"event":{"seq":15}}"""))
        server.enqueue(MockResponse().setBody("""{"event":{"seq":15}}"""))
        val first = client.send(base(), "da_test", "ses_fixed", RelayKind.PROGRESS, "第 1 步 phone_observe", "run:1")
        val second = client.send(base(), "da_test", "ses_fixed", RelayKind.PROGRESS, "第 1 步 phone_observe", "run:1")
        assertEquals(15L, first)
        assertEquals(15L, second)
        val a = server.takeRequest()
        val b = server.takeRequest()
        val aBody = a.body.readUtf8()
        assertEquals("/task/v1/relay/sessions/ses_fixed/messages", a.path)
        assertEquals("Bearer da_test", a.getHeader("Authorization"))
        assertFalse(a.path!!.contains("da_test"))
        assertEquals(aBody, b.body.readUtf8())
        val posted = kotlinx.serialization.json.Json.parseToJsonElement(aBody).jsonObject
        assertEquals("progress", posted["kind"]!!.jsonPrimitive.content)
        assertEquals("run:1", posted["request_id"]!!.jsonPrimitive.content)
    }

    @Test fun resultAndScreenshotUpload() {
        server.enqueue(MockResponse().setBody("""{"file_id":"fl_9","name":"screen-0003.png","size":4}"""))
        server.enqueue(MockResponse().setBody("""{"seq":8}"""))
        val fileId = client.upload(base(), "da_test", "ses_fixed", "screen-0003.png", byteArrayOf(1, 2, 3, 4))
        val seq = client.send(base(), "da_test", "ses_fixed", RelayKind.RESULT, "结束：succeeded", "run:9", listOf(fileId))
        assertEquals(8L, seq)
        val upload = server.takeRequest()
        assertEquals("/task/v1/relay/sessions/ses_fixed/files?name=screen-0003.png", upload.path)
        assertEquals("Bearer da_test", upload.getHeader("Authorization"))
        assertEquals(4, upload.body.readByteArray().size)
        val posted = kotlinx.serialization.json.Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("result", posted["kind"]!!.jsonPrimitive.content)
        assertEquals("fl_9", posted["attachments"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test fun workerRejectsInstructionWithoutCallingHub() {
        val error = assertThrows(RelayException::class.java) {
            client.send(base(), "da_test", "ses_fixed", "instruction", "do it", "run:1")
        }
        assertFalse(error.retryable)
        assertEquals(0, server.requestCount)
    }

    @Test fun errorBodyDoesNotLeakToken() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"rejected da_test and sk-abcdefghijklmnopqrstuvwxyz"}"""))
        val error = assertThrows(RelayException::class.java) {
            client.send(base(), "da_test", "ses_fixed", RelayKind.PROGRESS, "hi", "run:1")
        }
        assertEquals(401, error.status)
        assertFalse(error.retryable)
        assertFalse(error.message!!.contains("da_test"))
        assertFalse(error.message!!.contains("sk-abcdefghijklmnopqrstuvwxyz"))
        assertTrue(error.message!!.contains("***"))
    }

    @Test fun sessionIdFieldIsAccepted() {
        server.enqueue(MockResponse().setBody("""{"session_id":"ses_from_field"}"""))
        assertEquals("ses_from_field", client.join(server.url("/").toString().trimEnd('/'), "da_test", "INVITE", env))
        assertEquals("/v1/relay/join", server.takeRequest().path)
    }
}
