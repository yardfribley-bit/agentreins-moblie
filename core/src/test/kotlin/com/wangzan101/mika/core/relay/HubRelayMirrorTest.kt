package com.wangzan101.mika.core.relay

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HubRelayMirrorTest {
    private val env = RelayEnvironment("Android", "14 / API 34", "arm64-v8a")

    @Test fun retryKeepsRequestIdAndDoesNotThrow() {
        val fake = FakeRelay()
        fake.failSends = 1
        val mirror = mirror(fake, RelayConfig(enabled = true, token = "da_test", sessionId = "ses_fixed", joinCode = "CODE"))
        mirror.submit("run-1", RunEvent.TaskStarted(1, "查天气", "m"))
        mirror.drain()
        assertEquals(listOf("run-1:0", "run-1:0"), fake.sent.map { it.requestId })
        assertEquals(listOf(RelayKind.PROGRESS, RelayKind.PROGRESS), fake.sent.map { it.kind })
        assertTrue(fake.sent.all { it.text.startsWith("开始：") })
        assertEquals(11L, fake.seqs.last())
    }

    @Test fun networkErrorDoesNotEscapeAndGivesUpAfterAttempts() {
        val fake = FakeRelay()
        fake.failForever = true
        val errors = mutableListOf<String?>()
        val sleeps = mutableListOf<Long>()
        val mirror = mirror(
            fake,
            RelayConfig(enabled = true, token = "da_test", sessionId = "ses_fixed"),
            maxAttempts = 3,
            sleep = { sleeps += it },
            onError = { errors += it },
        )
        mirror.submit("run-1", RunEvent.TaskEnded(2, "failed", "打不开"))
        mirror.drain()
        assertEquals(0, mirror.pending())
        assertEquals(3, fake.sent.size)
        assertTrue(fake.sent.all { it.requestId == "run-1:0" && it.kind == RelayKind.RESULT })
        assertEquals(listOf(500L, 1000L), sleeps)
        assertTrue(errors.any { it == "network" })
    }

    @Test fun forbiddenRejoinsOnceThenSends() {
        val fake = FakeRelay()
        var session = "ses_old"
        val mirror = mirror(
            fake,
            config = { RelayConfig(enabled = true, token = "da_test", sessionId = session, joinCode = "CODE") },
            onSession = { session = it },
        )
        fake.failStatus = 403
        mirror.submit("run-1", RunEvent.State(1, "steer_received", "继续"))
        mirror.drain()
        assertEquals(listOf("join:CODE:Android"), fake.calls.filter { it.startsWith("join") })
        assertEquals("ses_new", session)
        assertEquals("ses_new", fake.sent.last().sessionId)
        assertEquals(RelayKind.PROGRESS, fake.sent.last().kind)
    }

    @Test fun disabledDropsAndJoinRequiresCode() {
        val fake = FakeRelay()
        val errors = mutableListOf<String?>()
        var cfg = RelayConfig(enabled = false, token = "da_test", joinCode = "CODE")
        val mirror = mirror(fake, config = { cfg }, onError = { errors += it })
        mirror.submit("run-1", RunEvent.TaskStarted(1, "目标", "m"))
        assertEquals(0, mirror.pending())
        mirror.joinNow()
        assertEquals(listOf("off"), errors)
        assertTrue(fake.calls.isEmpty())

        cfg = cfg.copy(enabled = true, token = "", alias = "phone", agentType = "agentreins-moblie", joinCode = "")
        mirror.joinNow()
        assertEquals("need join code", errors.last())
        assertEquals(listOf("connect"), fake.calls)
    }

    @Test fun connectThenJoinPersistsSession() {
        val fake = FakeRelay()
        var token = ""
        var session = ""
        val mirror = mirror(
            fake,
            config = {
                RelayConfig(enabled = true, token = token, alias = "phone", agentType = "agentreins-moblie", joinCode = "TOPIC", sessionId = session)
            },
            onIdentity = { token = it.token; true },
            onSession = { session = it },
        )
        mirror.joinNow()
        assertEquals("da_new", token)
        assertEquals("ses_new", session)
        assertEquals(listOf("connect", "join:TOPIC:Android"), fake.calls)
    }

    @Test fun screenshotUploadsOnceThenRetriesMessage() {
        val fake = FakeRelay()
        fake.failSends = 1
        val mirror = mirror(fake, RelayConfig(enabled = true, token = "da_test", sessionId = "ses_fixed", uploadScreenshots = true))
        mirror.submitShot("run-1", 3, byteArrayOf(9, 9, 9))
        mirror.drain()
        assertEquals(listOf("upload:screen-0003.png:3"), fake.calls.filter { it.startsWith("upload") })
        assertEquals(listOf("run-1:shot:3", "run-1:shot:3"), fake.sent.map { it.requestId })
        assertEquals(listOf("fl_1"), fake.sent.last().attachments)
    }

    @Test fun oversizedScreenshotIsNotUploaded() {
        val fake = FakeRelay()
        val mirror = mirror(
            fake,
            RelayConfig(enabled = true, token = "da_test", sessionId = "ses_fixed", uploadScreenshots = true),
            maxShot = 4,
        )
        mirror.submitShot("run-1", 1, byteArrayOf(1, 2, 3, 4, 5))
        mirror.drain()
        assertTrue(fake.calls.none { it.startsWith("upload") })
        assertTrue(fake.sent.single().text.contains("未上传"))
        assertTrue(fake.sent.single().attachments.isEmpty())
    }

    @Test fun queueDropsOldest() {
        val fake = FakeRelay()
        val mirror = mirror(fake, RelayConfig(enabled = true, token = "da_test", sessionId = "ses_fixed"), maxQueue = 5)
        repeat(8) { mirror.submit("run", RunEvent.State(it.toLong(), "s", "$it")) }
        assertEquals(5, mirror.pending())
        mirror.drain()
        assertEquals(listOf("3", "4", "5", "6", "7"), fake.sent.map { it.text.substringAfterLast(' ') })
    }

    @Test fun lostMembershipRejoinsWithTheSameCode() {
        val fake = FakeRelay()
        fake.member = false
        var session = "ses_old"
        val mirror = mirror(
            fake,
            config = { RelayConfig(enabled = true, token = "da_test", sessionId = session, joinCode = "CODE", agentId = "ag_x") },
            onSession = { session = it },
        )
        mirror.submit("run-1", RunEvent.State(1, "s", "1"))
        mirror.drain()
        assertEquals(listOf("member:ses_old"), fake.calls.filter { it.startsWith("member") })
        assertEquals(listOf("join:CODE:Android"), fake.calls.filter { it.startsWith("join") })
        assertEquals("ses_new", fake.sent.single().sessionId)
        assertEquals("ses_new", session)
    }

    @Test fun heartbeatWhileRunIsActive() {
        val fake = FakeRelay()
        val mirror = mirror(fake, RelayConfig(enabled = true, token = "da_test", sessionId = "ses_fixed"), heartbeatEveryMs = 0)
        val lease = mirror.retain()
        mirror.pump()
        assertTrue(fake.calls.contains("beat"))
        fake.calls.clear()
        lease.close()
        mirror.pump()
        assertFalse(fake.calls.contains("beat"))
    }

    private fun mirror(
        fake: FakeRelay,
        config: RelayConfig,
        maxAttempts: Int = 6,
        maxQueue: Int = 200,
        maxShot: Int = MAX_SCREENSHOT_BYTES,
        heartbeatEveryMs: Long = 25_000,
        sleep: (Long) -> Unit = {},
        onError: (String?) -> Unit = {},
        onSession: (String) -> Unit = {},
        onIdentity: (RelayIdentity) -> Boolean = { true },
    ) = mirror(fake, { config }, maxAttempts, maxQueue, maxShot, heartbeatEveryMs, sleep, onError, onSession, onIdentity)

    private fun mirror(
        fake: FakeRelay,
        config: () -> RelayConfig,
        maxAttempts: Int = 6,
        maxQueue: Int = 200,
        maxShot: Int = MAX_SCREENSHOT_BYTES,
        heartbeatEveryMs: Long = 25_000,
        sleep: (Long) -> Unit = {},
        onError: (String?) -> Unit = {},
        onSession: (String) -> Unit = {},
        onIdentity: (RelayIdentity) -> Boolean = { true },
    ) = HubRelayMirror(
        transport = fake,
        config = config,
        environment = { env },
        onSession = onSession,
        onIdentity = onIdentity,
        onError = onError,
        sleep = sleep,
        heartbeatEveryMs = heartbeatEveryMs,
        maxQueue = maxQueue,
        maxAttempts = maxAttempts,
        maxShot = maxShot,
        startWorker = false,
    )

    private class FakeRelay : RelayTransport {
        val calls = mutableListOf<String>()
        val sent = mutableListOf<Sent>()
        val seqs = mutableListOf<Long>()
        var failSends = 0
        var failForever = false
        var failStatus: Int? = null

        data class Sent(val requestId: String, val kind: String, val text: String, val attachments: List<String>, val sessionId: String)

        override fun connect(mcpUrl: String, alias: String, agentType: String): RelayIdentity {
            calls += "connect"
            return RelayIdentity("da_new", "ag_test")
        }

        override fun join(mcpUrl: String, token: String, code: String, environment: RelayEnvironment): String {
            calls += "join:$code:${environment.osName}"
            return "ses_new"
        }

        override fun send(
            mcpUrl: String, token: String, sessionId: String, kind: String, text: String, requestId: String, attachments: List<String>,
        ): Long? {
            sent += Sent(requestId, kind, text, attachments, sessionId)
            if (failForever) throw IOException("down")
            val status = failStatus
            if (status != null) {
                failStatus = null
                throw RelayException(status, false, "HTTP $status")
            }
            if (failSends > 0) {
                failSends--
                throw IOException("down")
            }
            seqs += 11
            return 11
        }

        override fun upload(mcpUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray): String {
            calls += "upload:$name:${bytes.size}"
            return "fl_1"
        }

        override fun heartbeat(mcpUrl: String, token: String, sessionId: String, environment: RelayEnvironment) {
            calls += "beat"
        }

        var member = true
        override fun whoami(mcpUrl: String, token: String): String? {
            calls += "whoami"
            return "ag_saved"
        }

        override fun memberOf(mcpUrl: String, token: String, sessionId: String): Boolean {
            calls += "member:$sessionId"
            return member
        }
    }
}
