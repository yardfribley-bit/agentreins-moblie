package com.wangzan101.mika.relay

import android.content.Context
import android.os.Build
import android.util.Log
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.relay.HubClaimDecision
import com.wangzan101.mika.core.relay.HubInbound
import com.wangzan101.mika.core.relay.HubInstructionFilter
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
    private val transport: RelayTransport = HubMcpClient(),
    startWorker: Boolean = true,
    private val busy: () -> Boolean = { false },
    private val startRun: (String) -> Unit = {},
    startInbound: Boolean = true,
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
        if (startInbound) Thread(::inbound, "mika-hub-inbound").apply { isDaemon = true }.start()
    }

    fun snapshot(): HubRelaySnapshot = settings.snapshot()

    fun setEnabled(on: Boolean) { settings.setEnabled(on); bump() }
    fun setMcpUrl(url: String) { settings.setMcpUrl(url) }
    fun setToken(token: String): Boolean = settings.setToken(token).also { bump() }
    fun clearToken() { settings.setToken(""); bump() }
    fun setJoinCode(code: String) { settings.setJoinCode(code) }
    fun setAlias(alias: String) { settings.setAlias(alias) }
    fun setAgentType(agentType: String) { settings.setAgentType(agentType) }
    fun setUploadScreenshots(on: Boolean) { settings.setUploadScreenshots(on); bump() }
    fun setClaimTasks(on: Boolean) { settings.setClaimTasks(on); bump() }
    fun setRunOwnerInstructions(on: Boolean) { settings.setRunOwnerInstructions(on); bump() }

    fun open(runId: String): RelayRun {
        mirror.claimReply(runId)
        return RelayRun(mirror, runId, mirror.retain())
    }

    fun submit(runId: String, event: RunEvent) = mirror.submit(runId, event)

    fun joinNow() { mirror.joinNow(); bump() }

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
            mirror.armReply(next.seq)
            startRun(next.text)
        }
        readWait = if (page.hasMore) 0 else HubInstructionFilter.MAX_WAIT_SECONDS
        return 0L
    }

    companion object {
        private const val TAG = "HubRelay"
        private const val PREFS = "hub_relay"

        fun create(context: Context): HubRelayRuntime {
            val app = context.applicationContext
            val prefs = HubRelaySettings(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KeystoreKeyCipher("mika-hub-relay-token"))
            return HubRelayRuntime(
                prefs,
                environment = {
                    RelayEnvironment(
                        osName = "Android",
                        osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" } + " / API " + Build.VERSION.SDK_INT,
                        architecture = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it.isNotBlank() } ?: "unknown",
                    )
                },
                busy = { (app as com.wangzan101.mika.MikaApp).taskState.busy.value },
                startRun = { goal -> TaskService.start(app, goal, prefs.hubConversationId()) },
            )
        }
    }
}

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
