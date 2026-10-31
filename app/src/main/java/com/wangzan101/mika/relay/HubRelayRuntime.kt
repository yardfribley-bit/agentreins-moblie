package com.wangzan101.mika.relay

import android.content.Context
import android.os.Build
import android.util.Log
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.relay.HubClaimDecision
import com.wangzan101.mika.core.relay.HubMcpClient
import com.wangzan101.mika.core.relay.HubRemoteInstruction
import com.wangzan101.mika.core.relay.HubTaskClaim
import com.wangzan101.mika.core.relay.HubRelayMirror
import com.wangzan101.mika.core.relay.RelayEnvironment
import com.wangzan101.mika.core.relay.RelayTransport
import com.wangzan101.mika.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程里的一份 Hub 镜像。任务把运行事件交给 [open] 得到的接收方;本地记录照旧写,这里失败不影响任务。
 */
class HubRelayRuntime(
    private val settings: HubRelaySettings,
    environment: () -> RelayEnvironment,
    transport: RelayTransport = HubMcpClient(),
    startWorker: Boolean = true,
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
        onError = { settings.setLastError(it); bump() },
        onSeq = { settings.setLastSeq(it); settings.setLastError(null); bump() },
        startWorker = startWorker,
    )

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

    fun open(runId: String): RelayRun = RelayRun(mirror, runId, mirror.retain())

    fun submit(runId: String, event: RunEvent) = mirror.submit(runId, event)

    fun joinNow() { mirror.joinNow(); bump() }

    /**
     * 认领开关关上时直接拒绝。打开了也只返回 [HubClaimDecision.NotImplemented] 或确认要求,
     * 不会启动任务。付款一类动作即使以后实现,也要先在手机上确认。
     */
    fun consider(instruction: HubRemoteInstruction): HubClaimDecision =
        HubTaskClaim.decide(settings.config().claimTasks, instruction)

    private fun bump() { _revision.value++ }

    companion object {
        private const val TAG = "HubRelay"
        private const val PREFS = "hub_relay"

        fun create(context: Context): HubRelayRuntime {
            val app = context.applicationContext
            return HubRelayRuntime(
                HubRelaySettings(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KeystoreKeyCipher("mika-hub-relay-token")),
                environment = {
                    RelayEnvironment(
                        osName = "Android",
                        osVersion = Build.VERSION.RELEASE.ifBlank { "unknown" } + " / API " + Build.VERSION.SDK_INT,
                        architecture = Build.SUPPORTED_ABIS.firstOrNull()?.takeIf { it.isNotBlank() } ?: "unknown",
                    )
                },
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
