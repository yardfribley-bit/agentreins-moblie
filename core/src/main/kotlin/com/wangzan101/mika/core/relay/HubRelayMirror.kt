package com.wangzan101.mika.core.relay

import com.wangzan101.mika.core.record.RunEvent
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把运行事件排队送到固定的 Hub 会话。网络失败只重试、丢最旧的,不抛给任务。
 * 还没有 session id 时用加入码 join 一次并交给 [onSession];不会创建会话。
 */
class HubRelayMirror(
    private val transport: RelayTransport,
    private val config: () -> RelayConfig,
    private val environment: () -> RelayEnvironment,
    private val log: (String) -> Unit = {},
    private val onSession: (String) -> Unit = {},
    private val onIdentity: (RelayIdentity) -> Boolean = { true },
    private val onAgentId: (String) -> Unit = {},
    private val onError: (String?) -> Unit = {},
    private val onSeq: (Long) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    private val heartbeatEveryMs: Long = 25_000,
    private val maxQueue: Int = 200,
    private val maxAttempts: Int = 6,
    private val maxShot: Int = MAX_SCREENSHOT_BYTES,
    startWorker: Boolean = true,
) {
    private val lock = Object()
    private val queue = ArrayDeque<Outbound>()
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val replies = HashMap<String, Long>()
    private var pendingReply: Long? = null
    private var checkedSession: String? = null
    private var whoamiTried = false
    @Volatile private var activeRuns = 0
    @Volatile private var lastBeat = now()

    init {
        if (startWorker) Thread(::loop, "mika-hub-relay").apply { isDaemon = true }.start()
    }

    /** 下一次打开的运行,其最终 result 要回复这条指令。 */
    fun armReply(seq: Long) { synchronized(lock) { pendingReply = seq } }

    /** 运行记录开张时把挂起的回复号记到这次 [runId] 上。 */
    fun claimReply(runId: String) {
        synchronized(lock) {
            val seq = pendingReply ?: return
            pendingReply = null
            replies[runId] = seq
        }
    }

    /** 有任务在跑时才心跳。关掉只是不再心跳,队列里的消息仍会发出。 */
    fun retain(): AutoCloseable {
        synchronized(lock) {
            activeRuns++
            lock.notifyAll()
        }
        return AutoCloseable {
            synchronized(lock) {
                if (activeRuns > 0) activeRuns--
                lock.notifyAll()
            }
        }
    }

    fun submit(runId: String, event: RunEvent) {
        try {
            if (!config().enabled) return
            val index = counters.getOrPut(runId) { AtomicInteger(0) }.getAndIncrement()
            val message = RelayEventText.map(runId, index, event) ?: return
            val reply = if (message.kind == RelayKind.RESULT) synchronized(lock) { replies[runId] } else null
            enqueue(message, replyTo = reply)
        } catch (t: Throwable) {
            log("drop")
        }
    }

    fun submitShot(runId: String, mediaId: Int, png: ByteArray) {
        try {
            val cfg = config()
            if (!cfg.enabled || !cfg.uploadScreenshots) return
            val tooBig = png.size > maxShot
            val text = if (tooBig) "画面 $mediaId 超过上限，未上传" else "画面 $mediaId"
            enqueue(
                RelayMessage("$runId:shot:$mediaId", RelayKind.PROGRESS, text),
                bytes = if (tooBig) null else png,
                fileName = "screen-%04d.png".format(Locale.US, mediaId),
            )
        } catch (t: Throwable) {
            log("drop shot")
        }
    }

    /** 设置页上的「加入」。失败写在 [onError],不抛。 */
    fun joinNow() {
        val cfg = config()
        try {
            if (!cfg.enabled) {
                onError("off")
                return
            }
            val token = resolveToken(cfg).token
            if (cfg.joinCode.isBlank()) {
                onError(if (cfg.sessionId.isBlank()) "need join code" else null)
                return
            }
            join(cfg, token)
            onError(null)
        } catch (e: RelayException) {
            log("join ${e.status}")
            onError(e.message)
        } catch (e: IOException) {
            log("join network")
            onError("network")
        } catch (e: Exception) {
            log("join")
            onError(e.message?.take(200) ?: "error")
        }
    }

    /** 测试里把队列走完。失败会按次退避,由 [sleep] 决定要不要真的等。 */
    fun drain(limit: Int = 500) {
        var n = 0
        while (n++ < limit && pending() > 0) step()
    }

    fun pump() = step()

    internal fun pending(): Int = synchronized(lock) { queue.size }

    private fun enqueue(message: RelayMessage, bytes: ByteArray? = null, fileName: String? = null, replyTo: Long? = null) {
        synchronized(lock) {
            if (!config().enabled) return
            while (queue.size >= maxQueue) queue.removeFirst()
            queue.addLast(Outbound(message.requestId, message.kind, message.text, bytes, fileName, replyTo = replyTo))
            lock.notifyAll()
        }
    }

    private fun loop() {
        while (true) {
            try {
                val waitMs = synchronized(lock) {
                    if (queue.isNotEmpty() || heartbeatDue()) 0L else heartbeatEveryMs.coerceAtLeast(1)
                }
                if (waitMs > 0) {
                    synchronized(lock) {
                        if (queue.isEmpty()) lock.wait(waitMs)
                    }
                    continue
                }
                step()
            } catch (t: Throwable) {
                log("loop")
                try {
                    sleep(1_000)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun step() {
        val item = synchronized(lock) { queue.firstOrNull() }
        if (item == null) {
            if (heartbeatDue()) beat()
            return
        }
        when (try {
            deliver(item)
        } catch (t: Throwable) {
            log("deliver")
            Outcome.Drop
        }) {
            Outcome.Success, Outcome.Drop -> synchronized(lock) {
                if (queue.firstOrNull() === item) queue.removeFirst()
            }
            Outcome.Retry -> {
                val keep = synchronized(lock) {
                    if (queue.firstOrNull() !== item) false
                    else {
                        item.attempts++
                        if (item.attempts >= maxAttempts) {
                            queue.removeFirst()
                            false
                        } else true
                    }
                }
                if (keep) sleep(backoff(item.attempts))
            }
        }
    }

    private fun deliver(item: Outbound): Outcome {
        val cfg = config()
        if (!cfg.enabled) {
            synchronized(lock) { queue.clear() }
            return Outcome.Drop
        }
        return try {
            val token = resolveToken(cfg).token
            var session = sessionFor(cfg, token)
            var rejoined = false
            while (true) {
                try {
                    post(cfg, token, session, item)
                    break
                } catch (e: RelayException) {
                    if (!rejoined && (e.status == 403 || e.status == 404) && cfg.joinCode.isNotBlank()) {
                        rejoined = true
                        session = join(cfg, token)
                        continue
                    }
                    throw e
                }
            }
            Outcome.Success
        } catch (e: RelayException) {
            log("send ${e.status}")
            onError(e.message)
            if (e.retryable) Outcome.Retry else Outcome.Drop
        } catch (e: IOException) {
            log("send network")
            onError("network")
            Outcome.Retry
        } catch (e: Exception) {
            log("send")
            onError(e.message?.take(200) ?: "error")
            Outcome.Drop
        }
    }

    private fun post(cfg: RelayConfig, token: String, session: String, item: Outbound) {
        if (item.bytes != null && item.fileId == null) {
            item.fileId = transport.upload(cfg.mcpUrl, token, session, item.fileName ?: "screen.png", item.bytes)
        }
        val seq = transport.send(
            cfg.mcpUrl, token, session, item.kind, item.text, item.requestId,
            attachments = listOfNotNull(item.fileId),
            replyTo = item.replyTo,
        )
        lastBeat = now()
        if (seq != null) onSeq(seq) else onError(null)
    }

    private fun beat() {
        val cfg = config()
        if (!cfg.enabled || cfg.token.isBlank() || cfg.sessionId.isBlank() || cfg.tokenUnreadable) return
        try {
            transport.heartbeat(cfg.mcpUrl, cfg.token, cfg.sessionId, environment())
            lastBeat = now()
        } catch (e: RelayException) {
            lastBeat = now()
            log("heartbeat ${e.status}")
            onError(e.message)
        } catch (e: IOException) {
            lastBeat = now()
            log("heartbeat network")
            onError("network")
        }
    }

    private fun heartbeatDue(): Boolean {
        val cfg = config()
        return activeRuns > 0 && cfg.enabled && cfg.sessionId.isNotBlank() && cfg.token.isNotBlank() && !cfg.tokenUnreadable &&
            now() - lastBeat >= heartbeatEveryMs
    }

    private fun resolveToken(cfg: RelayConfig): RelayIdentity {
        if (cfg.tokenUnreadable) throw RelayException(0, false, "token unreadable")
        if (cfg.token.isNotBlank()) {
            if (cfg.agentId.isBlank() && !whoamiTried) {
                whoamiTried = true
                recoverAgentId(cfg)
            }
            return RelayIdentity(cfg.token, cfg.agentId.ifBlank { null })
        }
        if (cfg.alias.isBlank() || cfg.agentType.isBlank()) throw RelayException(0, false, "need token")
        val identity = transport.connect(cfg.mcpUrl, cfg.alias, cfg.agentType)
        if (!onIdentity(identity)) throw RelayException(0, false, "token unreadable")
        return identity
    }

    private fun recoverAgentId(cfg: RelayConfig) {
        val id = try {
            transport.whoami(cfg.mcpUrl, cfg.token)
        } catch (e: IOException) {
            log("whoami")
            null
        }
        if (!id.isNullOrBlank()) onAgentId(id)
    }

    /** 存着的会话若已不是成员,用加入码再进一次。 */
    private fun sessionFor(cfg: RelayConfig, token: String): String {
        val stored = cfg.sessionId
        if (stored.isBlank()) return join(cfg, token)
        if (checkedSession == stored) return stored
        val member = transport.memberOf(cfg.mcpUrl, token, stored)
        if (!member) {
            checkedSession = null
            return join(cfg, token).also { checkedSession = it }
        }
        checkedSession = stored
        return stored
    }

    private fun join(cfg: RelayConfig, token: String): String {
        if (cfg.joinCode.isBlank()) throw RelayException(0, false, "need join code")
        val id = try {
            transport.join(cfg.mcpUrl, token, cfg.joinCode, environment())
        } catch (e: RelayException) {
            // 409 = 已经是这个会话的成员,不是失败。沿用保存的会话继续收指令。
            if (e.status == 409 && cfg.sessionId.isNotBlank()) cfg.sessionId else throw e
        }
        onSession(id)
        checkedSession = id
        return id
    }

    private fun backoff(attempt: Int): Long {
        val exp = (attempt - 1).coerceIn(0, 6)
        return (500L shl exp).coerceAtMost(30_000L)
    }

    private class Outbound(
        val requestId: String,
        val kind: String,
        val text: String,
        val bytes: ByteArray?,
        val fileName: String?,
        val replyTo: Long? = null,
        var fileId: String? = null,
        var attempts: Int = 0,
    )

    private enum class Outcome { Success, Retry, Drop }
}
