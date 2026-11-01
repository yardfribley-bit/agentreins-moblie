package com.wangzan101.mika.audio

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiveTranscriberTest {
    @Test fun wavHeaderMatchesMono16kPcm() {
        val wav = LiveTranscriber.wav(byteArrayOf(1, 0, 2, 0))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals(40, b.getInt(4)); assertEquals(16000, b.getInt(24))
        assertEquals(32000, b.getInt(28)); assertEquals(4, b.getInt(40))
        assertArrayEquals(byteArrayOf(1, 0, 2, 0), wav.copyOfRange(44, wav.size))
    }
    @Test fun transcribesShortJsonResponseAndUsesMultipart() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"text\":\"主播讲话\"}"))
            val client = LiveTranscriber(server.url("/audio/transcriptions").toString(), "test-key", "test-asr")
            assertEquals("主播讲话", client.transcribe(byteArrayOf(1, 0)))
            val request = server.takeRequest()
            assertEquals("Bearer test-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("speech.wav")); assertTrue(body.contains("test-asr"))
        }
    }
    @Test fun doesNotFollowRedirectsOrExposeResponseBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/secret").setBody("sensitive-server-message"))
            val error = runCatching { LiveTranscriber(server.url("/asr").toString(), "test-key", "asr").transcribe(byteArrayOf(0, 0)) }.exceptionOrNull()
            assertNotNull(error); assertTrue(error!!.message!!.contains("302"))
            assertFalse(error.message!!.contains("sensitive")); assertEquals(1, server.requestCount)
        }
    }
    @Test fun distinguishesNetworkFailures() {
        assertTrue(LiveTranscriber.networkFailure(java.net.UnknownHostException()).message!!.contains("ASR_DNS"))
        assertTrue(LiveTranscriber.networkFailure(java.net.SocketTimeoutException()).message!!.contains("ASR_TIMEOUT"))
        assertTrue(LiveTranscriber.networkFailure(javax.net.ssl.SSLHandshakeException("private-details")).message!!.contains("ASR_TLS"))
        assertTrue(LiveTranscriber.networkFailure(java.net.ConnectException()).retryable)
    }
    @Test fun http401NamesProviderMismatchWithoutTheKey() {
        val key = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz"
        val message = LiveTranscriber.httpFailure(
            401, """{"error":{"message":"invalid api key $key"}}""", key,
            "https://api.openai.com/v1/audio/transcriptions",
        ).message!!
        assertTrue(message.contains("OpenRouter"))
        assertTrue(message.contains("401"))
        assertFalse(message.contains(key))
        assertFalse(message.contains("abcdefghijklmnopqrstuvwxyz"))
        val sameProvider = LiveTranscriber.httpFailure(401, "{}", "sk-" + "abcdefghijklmnopqrstuvwxyz", "https://api.openai.com/v1/audio/transcriptions").message!!
        assertTrue(sameProvider.contains("Key 无效或已失效"))
    }

    @Test fun givesActionableHttpErrorWithoutLeakingKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"Region restricted; token test-key"}}"""))
            val error = runCatching { LiveTranscriber(server.url("/asr").toString(), "test-key", "asr").transcribe(byteArrayOf(0, 0)) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message!!.contains("HTTP 403"))
            assertTrue(error.message!!.contains("Region restricted"))
            assertFalse(error.message!!.contains("test-key"))
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun retriesTransientServerFailureOnce() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            server.enqueue(MockResponse().setBody("{\"text\":\"recovered\"}"))
            assertEquals("recovered", LiveTranscriber(server.url("/asr").toString(), "test-key", "asr").transcribe(byteArrayOf(0, 0)))
            assertEquals(2, server.requestCount)
        }
    }
}
