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
    /** 进度攒够这么多条就发一批。结果不攒，单独发。 */
    private val progressBatch: Int = 40,
    /** 进度最久压这么久。长任务大约半分钟一条进度，外加最后的 result。 */
    private val progressFlushMs: Long = 30_000,
    startWorker: Boolean = true,
) {
    private val lock = Object()
    private val queue = ArrayDeque<Outbound>()
    private val progressHold = ArrayDeque<RelayMessage>()
    private var progressSince = 0L
    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private class ReplyBinding(val seq: Long, val requestId: String?, val note: String?)
    private val replies = HashMap<String, ReplyBinding>()
    private var pendingReply: ReplyBinding? = null
    /** 已经为这条指令排队过终态，避免 result 和 question 各发一遍。 */
    private val resultSeqs = HashSet<Long>()
    @Volatile var onResultQueued: (requestId: String, seq: Long, text: String) -> Unit = { _, _, _ -> }
    @Volatile var onResultSent: (requestId: String, seq: Long) -> Unit = { _, _ -> }
    private var checkedSession: String? = null
    private var whoamiTried = false
    @Volatile private var activeRuns = 0
    @Volatile private var lastBeat = now()

    init {
        if (startWorker) Thread(::loop, "mika-hub-relay").apply { isDaemon = true }.start()
    }

    /** 下一次打开的运行,其最终结果要回复这条指令。[resultNote] 附在末尾，说明没下载到的附件。 */
    fun armReply(seq: Long, resultNote: String? = null, requestId: String? = null) {
        synchronized(lock) {
            pendingReply = ReplyBinding(seq, requestId?.trim()?.ifBlank { null }, resultNote?.takeIf { it.isNotBlank() })
        }
    }

    /** 运行记录开张时把挂起的回复记到这次 [runId] 上。 */
    fun claimReply(runId: String) {
        synchronized(lock) {
            val pending = pendingReply ?: return
            pendingReply = null
            replies[runId] = pending
        }
    }

    /** 只在序号对得上时认领，别的运行开张不会把这条指令的回复抢走。 */
    fun claimReply(runId: String, seq: Long) {
        synchronized(lock) {
            val pending = pendingReply ?: return
            if (pending.seq != seq) return
            pendingReply = null
            replies[runId] = pending
        }
    }

    /**
     * 把这条指令的终态排进发送队列。同一 seq 只排一次。
     * [png] 会先经 `diaodu_relay_upload` 再放进 attachments。
     */
    fun postFinal(requestId: String, text: String, replyTo: Long, kind: String = RelayKind.RESULT, fileIds: List<String> = emptyList(), png: ByteArray? = null, pngName: String? = null): Boolean {
        val queued = synchronized(lock) {
            if (!config().enabled) return false
            if (replyTo in resultSeqs) return false
            resultSeqs += replyTo
            true
        }
        if (!queued) return false
        enqueue(RelayMessage(requestId, kind, text), bytes = png, fileName = pngName, replyTo = replyTo, fileIds = fileIds)
        try {
            onResultQueued(requestId, replyTo, text)
        } catch (_: Throwable) {
            log("result queued")
        }
        return true
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
            if (message.kind == RelayKind.PROGRESS) {
                holdProgress(message)
                return
            }
            flushProgress(force = true)
            val binding = if (message.kind == RelayKind.RESULT) synchronized(lock) { replies[runId] } else null
            if (binding != null && synchronized(lock) { binding.seq in resultSeqs }) return
            val id = binding?.requestId?.let { HubResultText.requestId(it, binding.seq) } ?: message.requestId
            var text = message.text
            val note = binding?.note
            if (!note.isNullOrBlank()) text = HubAttachmentText.appendResult(text, note)
            if (binding != null && HubResultText.isSafe(binding.requestId)) text = HubResultText.withRequestId(text, binding.requestId)
            if (binding != null) synchronized(lock) { resultSeqs += binding.seq }
            enqueue(RelayMessage(id, message.kind, text), replyTo = binding?.seq)
            if (binding != null) {
                try {
                    onResultQueued(id, binding.seq, text)
                } catch (_: Throwable) {
                    log("result queued")
                }
            }
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
            flushProgress(force = true)
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

    /** 测试里把队列走完。失败会按次退避,由 [sleep] 决定要不要真的等。没攒满的进度也一起发出。 */
    fun drain(limit: Int = 500) {
        flushProgress(force = true)
        var n = 0
        while (n++ < limit && pending() > 0) step()
    }

    fun pump() {
        flushProgress(force = false)
        step()
    }

    internal fun pending(): Int = synchronized(lock) { queue.size }

    /** 进度先攒着。换了一次运行、正文快满、条数够了或时间到了，才合成一条发出。 */
    private fun holdProgress(message: RelayMessage) {
        synchronized(lock) {
            val incoming = message.requestId.substringBeforeLast(':')
            if (progressHold.isNotEmpty() && progressHold.first().requestId.substringBeforeLast(':') != incoming) {
                flushProgressLocked()
            }
            val nextChars = heldChars() + message.text.length + if (progressHold.isEmpty()) 0 else 1
            if (progressHold.isNotEmpty() && nextChars > RELAY_MAX_TEXT) flushProgressLocked()
            if (progressHold.isEmpty()) progressSince = now()
            progressHold.addLast(message)
            val due = progressHold.size >= progressBatch || now() - progressSince >= progressFlushMs
            if (due) flushProgressLocked() else lock.notifyAll()
        }
    }

    private fun flushProgress(force: Boolean) {
        synchronized(lock) {
            if (progressHold.isEmpty()) return
            val due = force || progressHold.size >= progressBatch || now() - progressSince >= progressFlushMs ||
                heldChars() >= RELAY_MAX_TEXT
            if (due) flushProgressLocked()
        }
    }

    private fun flushProgressLocked() {
        if (progressHold.isEmpty()) return
        val items = progressHold.toList()
        progressHold.clear()
        val text = RelayEventText.clip(items.joinToString("\n") { it.text })
        enqueue(RelayMessage(items.first().requestId, RelayKind.PROGRESS, text))
    }

    private fun heldChars(): Int {
        if (progressHold.isEmpty()) return 0
        return progressHold.sumOf { it.text.length } + progressHold.size - 1
    }

    private fun enqueue(
        message: RelayMessage,
        bytes: ByteArray? = null,
        fileName: String? = null,
        replyTo: Long? = null,
        fileIds: List<String> = emptyList(),
    ) {
        synchronized(lock) {
            if (!config().enabled) return
            while (queue.size >= maxQueue) queue.removeFirst()
            queue.addLast(Outbound(message.requestId, message.kind, message.text, bytes, fileName, replyTo = replyTo, fileIds = fileIds))
            lock.notifyAll()
        }
    }

    private fun loop() {
        while (true) {
            try {
                val waitMs = synchronized(lock) {
                    when {
                        queue.isNotEmpty() -> 0L
                        progressHold.isNotEmpty() -> (progressFlushMs - (now() - progressSince)).coerceAtLeast(0L)
                        heartbeatDue() -> 0L
                        else -> heartbeatEveryMs.coerceAtLeast(1)
                    }
                }
                if (waitMs > 0) {
                    synchronized(lock) {
                        if (queue.isEmpty()) lock.wait(waitMs)
                    }
                    continue
                }
                flushProgress(force = false)
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
                    val replyTo = item.replyTo
                    if ((item.kind == RelayKind.RESULT || item.kind == RelayKind.QUESTION) && replyTo != null) {
                        try {
                            onResultSent(item.requestId, replyTo)
                        } catch (_: Throwable) {
                            log("result sent")
                        }
                    }
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
            attachments = (listOfNotNull(item.fileId) + item.fileIds).distinct(),
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
        val fileIds: List<String> = emptyList(),
        var fileId: String? = null,
        var attempts: Int = 0,
    )

    private enum class Outcome { Success, Retry, Drop }
}
