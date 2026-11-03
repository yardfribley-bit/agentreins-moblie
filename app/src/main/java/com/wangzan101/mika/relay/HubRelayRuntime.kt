package com.wangzan101.mika.relay

import android.content.Context
import android.os.Build
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.relay.HubAttachmentIntake
import com.wangzan101.mika.core.relay.HubClaimDecision
import com.wangzan101.mika.core.relay.HubInbound
import com.wangzan101.mika.core.relay.HubInstruction
import com.wangzan101.mika.core.relay.HubInstructionFilter
import com.wangzan101.mika.core.relay.HubOwnerLaunch
import com.wangzan101.mika.core.relay.HubInflight
import com.wangzan101.mika.core.relay.HubPreparedTask
import com.wangzan101.mika.core.relay.HubResultText
import com.wangzan101.mika.core.relay.HubRunOutcome
import com.wangzan101.mika.core.relay.HubTerminal
import com.wangzan101.mika.core.relay.RelayKind
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.relay.RelayConfig
import com.wangzan101.mika.core.relay.HubMcpClient
import com.wangzan101.mika.core.relay.HubRemoteInstruction
import com.wangzan101.mika.core.relay.HubRunGate
import com.wangzan101.mika.core.relay.HubTaskClaim
import com.wangzan101.mika.core.relay.HubRelayMirror
import com.wangzan101.mika.core.relay.RelayEnvironment
import com.wangzan101.mika.core.relay.RelayTransport
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.task.TaskService
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程里的一份 Hub 镜像。任务把运行事件交给 [open] 得到的接收方;本地记录照旧写,这里失败不影响任务。
 */
class HubRelayRuntime(
    private val settings: HubRelaySettings,
    environment: () -> RelayEnvironment,
    private val transport: RelayTransport = HubMcpClient(log = { Log.i("HubMcp", it) }),
    startWorker: Boolean = true,
    private val busy: () -> Boolean = { false },
    private val startRun: (HubGoal) -> Unit = {},
    private val prepare: (RelayConfig, HubInstruction) -> HubPreparedTask =
        { _, instruction -> HubPreparedTask(instruction.text, null) },
    startInbound: Boolean = true,
    private val onListen: () -> Unit = {},
    private val log: (String) -> Unit = { Log.w(TAG, it) },
) {
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val mirror = HubRelayMirror(
        transport = transport,
        config = { settings.config() },
        environment = environment,
        log = log,
        onSession = { settings.setSessionId(it); settings.setLastError(null); bump() },
        onIdentity = { identity ->
            val ok = settings.setToken(identity.token)
            identity.agentId?.takeIf { it.isNotBlank() }?.let { settings.setAgentId(it) }
            bump()
            ok
        },
        onAgentId = { settings.setAgentId(it); bump() },
        onError = { settings.setLastError(it); bump() },
        onSeq = { settings.setLastSeq(it); settings.setLastError(null); bump() },
        startWorker = startWorker,
    )

    private val gate = HubRunGate()
    private var readWait = HubInstructionFilter.MAX_WAIT_SECONDS
    private var backoffMs = 1_000L

    init {
        mirror.onResultQueued = { _, seq, text ->
            settings.inflight()?.let { flight ->
                if (flight.seq == seq) settings.setInflight(flight.copy(pendingText = text))
            }
        }
        mirror.onResultSent = { id, seq ->
            settings.markResultSent(id)
            val flight = settings.inflight()
            if (flight != null && flight.seq == seq) settings.setInflight(null)
            settings.setHandledSeq(maxOf(settings.handledSeq(), seq))
        }
        if (startInbound) Thread(::inbound, "mika-hub-inbound").apply { isDaemon = true }.start()
    }

    fun snapshot(): HubRelaySnapshot = settings.snapshot()

    fun setEnabled(on: Boolean) { settings.setEnabled(on); bump(); onListen() }
    fun setMcpUrl(url: String) { settings.setMcpUrl(url) }
    fun setToken(token: String): Boolean = settings.setToken(token).also { bump() }
    fun clearToken() { settings.setToken(""); bump(); onListen() }
    fun setJoinCode(code: String) { settings.setJoinCode(code) }
    fun setAlias(alias: String) { settings.setAlias(alias) }
    fun setAgentType(agentType: String) { settings.setAgentType(agentType) }
    fun setUploadScreenshots(on: Boolean) { settings.setUploadScreenshots(on); bump() }
    fun setClaimTasks(on: Boolean) { settings.setClaimTasks(on); bump() }
    fun setRunOwnerInstructions(on: Boolean) { settings.setRunOwnerInstructions(on); bump(); onListen() }

    fun open(runId: String, hubSeq: Long? = null): RelayRun {
        if (hubSeq != null) mirror.claimReply(runId, hubSeq)
        return RelayRun(mirror, runId, mirror.retain())
    }

    fun bindRun(seq: Long, runKey: String) {
        val flight = settings.inflight() ?: return
        if (flight.seq == seq) settings.setInflight(flight.copy(runKey = runKey))
    }

    /** 把这次 Hub 运行的终态发回原会话。成功、失败、拒答、需要补充、提问都走这里。 */
    fun deliverRunResult(seq: Long, requestId: String?, outcome: HubRunOutcome, text: String, png: ByteArray? = null) {
        val flight = settings.inflight()
        val note = if (flight != null && flight.seq == seq) flight.resultNote else null
        val rawId = requestId ?: if (flight != null && flight.seq == seq) flight.requestId else null
        val id = HubResultText.requestId(rawId, seq)
        if (settings.wasResultSent(id)) {
            if (flight != null && flight.seq == seq) settings.setInflight(null)
            settings.setHandledSeq(maxOf(settings.handledSeq(), seq))
            return
        }
        val kind = HubResultText.kind(outcome)
        if (flight != null && flight.seq == seq) settings.setInflight(flight.copy(pendingKind = kind))
        val body = HubResultText.body(outcome, text, id, note)
        mirror.postFinal(id, body, seq, kind, png = png?.takeIf { it.size >= 8 && it[0] == 0x89.toByte() && it[1] == 0x50.toByte() }, pngName = "hub-screen.png")
    }

    /** 进程重启或服务被杀掉时，还没送达的指令改报中断，避免 Hub 一直等。 */
    fun reportAbandoned(runs: List<StoredRun> = emptyList()) {
        val flight = settings.inflight() ?: return
        val seq = flight.seq
        val id = HubResultText.requestId(flight.requestId, seq)
        if (settings.wasResultSent(id)) {
            settings.setInflight(null)
            settings.setHandledSeq(maxOf(settings.handledSeq(), seq))
            return
        }
        val pending = flight.pendingText
        val matched = flight.runKey?.let { key -> runs.firstOrNull { it.id.value == key } }
        val reply = matched?.reply
        val succeeded = matched?.runSucceeded
        val text = when {
            !pending.isNullOrBlank() -> pending
            reply != null -> {
                val terminal = if (succeeded == false) HubTerminal.Failed else HubTerminal.Succeeded
                HubResultText.body(HubResultText.outcome(terminal, reply), reply, id, flight.resultNote)
            }
            else -> HubResultText.body(HubRunOutcome.Interrupted, HubResultText.INTERRUPTED, id, flight.resultNote)
        }
        val kind = flight.pendingKind ?: RelayKind.RESULT
        mirror.postFinal(id, text, seq, if (pending.isNullOrBlank() && reply == null) RelayKind.RESULT else kind)
    }

    fun submit(runId: String, event: RunEvent) = mirror.submit(runId, event)

    fun joinNow() { mirror.joinNow(); bump(); onListen() }

    /**
     * 认领开关关上时直接拒绝。打开了也只返回 [HubClaimDecision.NotImplemented] 或确认要求,
     * 不会启动任务。付款一类动作即使以后实现,也要先在手机上确认。
     */
    fun consider(instruction: HubRemoteInstruction): HubClaimDecision =
        HubTaskClaim.decide(settings.config().claimTasks, instruction)

    private fun bump() { _revision.value++ }

    /**
     * 加入之后一直收老板指令。确认用 `diaodu_relay_ack`(待确认计数),做完由镜像把 result 回复到那条 seq。
     * 付款、转账、删除、对外发消息、输入密码走和聊天一样的确认,这里不会自动同意。
     */
    private fun inbound() {
        while (true) {
            val pause = try {
                tick()
            } catch (e: IOException) {
                log("inbound")
                val wait = backoffMs
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                wait
            } catch (t: Throwable) {
                log("inbound")
                1_000L
            }
            if (pause > 0) {
                try {
                    Thread.sleep(pause)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun tick(): Long {
        val cfg = settings.config()
        if (!cfg.enabled || !cfg.runOwnerInstructions || cfg.token.isBlank() || cfg.sessionId.isBlank()) {
            readWait = HubInstructionFilter.MAX_WAIT_SECONDS
            return 1_000L
        }
        if (!transport.memberOf(cfg.mcpUrl, cfg.token, cfg.sessionId)) {
            if (cfg.joinCode.isNotBlank()) joinNow()
            return 2_000L
        }
        val after = settings.readCursor()
        val page = transport.read(cfg.mcpUrl, cfg.token, cfg.sessionId, after, readWait)
        Log.i("HubMcp", inboundReadLog(after, page.nextCursor, page.events.size))
        backoffMs = 1_000L
        val plan = HubInbound.plan(page, settings.handledSeq())
        val ack = plan.ackSeq
        if (ack != null) transport.ack(cfg.mcpUrl, cfg.token, cfg.sessionId, ack)
        if (plan.fresh.isNotEmpty()) settings.setInstructionQueue(HubInbound.enqueue(settings.instructionQueue(), plan.fresh))
        if (ack != null) settings.setHandledSeq(ack)
        if (page.nextCursor > after) settings.setReadCursor(page.nextCursor)
        val queued = settings.instructionQueue()
        val (rest, next) = gate.poll(System.currentTimeMillis(), busy(), queued)
        if (rest != queued) settings.setInstructionQueue(rest)
        if (next != null) {
            val prepared = HubOwnerLaunch.decorate(next) { prepare(cfg, it) }
            gate.noteStarting(System.currentTimeMillis())
            settings.setInflight(HubInflight(next.seq, next.requestId, prepared.resultNote))
            mirror.armReply(next.seq, prepared.resultNote, next.requestId)
            startRun(HubGoal(prepared.goal, next.seq, next.requestId))
        }
        readWait = if (page.hasMore) 0 else HubInstructionFilter.MAX_WAIT_SECONDS
        return 0L
    }

    companion object {
        private const val TAG = "HubRelay"
        private const val PREFS = "hub_relay"

        /** 一次长轮询顺利返回时打一行,方便在设备上确认收指令循环还在走。不含令牌和正文。 */
        internal fun inboundReadLog(after: Long, next: Long, events: Int): String =
            "inbound read start after=$after next=$next events=$events"

        fun create(context: Context): HubRelayRuntime {
            val app = context.applicationContext
            val prefs = HubRelaySettings(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KeystoreKeyCipher("mika-hub-relay-token"))
            val client = HubMcpClient(log = { Log.i("HubMcp", it) })
            val intake = HubAttachmentIntake(
                transport = client,
                write = { name, bytes -> HubInboxWriter(app, prefs.hubConversationId()).write(name, bytes) },
                gallery = MediaStoreGallery(app),
                sleep = { ms -> if (ms > 0) Thread.sleep(ms) },
            )
            return HubRelayRuntime(
                prefs,
                environment = {
                    RelayEnvironment(
                        osName = "Android",
                        osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" } + " / API " + Build.VERSION.SDK_INT,
                        architecture = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it.isNotBlank() } ?: "unknown",
                    )
                },
                transport = client,
                busy = { (app as MikaApp).taskState.busy.value },
                startRun = { goal ->
                    TaskService.start(app, goal.text, prefs.hubConversationId(), fromHub = true, hubSeq = goal.seq, hubRequestId = goal.requestId)
                },
                prepare = { cfg, instruction -> intake.prepare(cfg, instruction) },
                onListen = { HubRelayService.sync(app) },
            )
        }
    }
}

data class HubGoal(val text: String, val seq: Long, val requestId: String?)

/** 一次任务挂上镜像的接收方。 [close] 只停心跳,已经进队列的消息还会发。 */
class RelayRun(
    private val mirror: HubRelayMirror,
    private val runId: String,
    private val lease: AutoCloseable,
) : RunSink, AutoCloseable {
    override fun event(e: RunEvent) = mirror.submit(runId, e)
    override fun media(mediaId: Int, png: ByteArray) = mirror.submitShot(runId, mediaId, png)
    override fun close() = lease.close()
}
