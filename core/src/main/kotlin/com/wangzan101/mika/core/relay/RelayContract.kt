package com.wangzan101.mika.core.relay

import java.io.IOException

/** Hub 根地址。本地 Hub 可以不带 `/task` 前缀,路径仍是 `/v1/...`。 */
const val DEFAULT_HUB_BASE = "https://www.chuhaijian.com/task"

const val DEFAULT_RELAY_ALIAS = "agentreins-moblie"
const val DEFAULT_RELAY_AGENT_TYPE = "agentreins-moblie"

/** 发给 Hub 的正文上限(协议)。 */
const val RELAY_MAX_TEXT = 8000

/** 截图默认不上传;打开后超过这个字节数的画面只记一句,不传字节。 */
const val MAX_SCREENSHOT_BYTES = 1_500_000

object RelayKind {
    const val PROGRESS = "progress"
    const val RESULT = "result"
}

/**
 * 这一台手机要对齐的 Hub。令牌由调用方保管;[token] 为空且 [tokenUnreadable] 为假时,
 * 才用 [alias] / [agentType] 走一次 `POST /v1/connect`。
 * 手机不创建会话:只有 [joinCode](Topic 码或一次性 worker 邀请)或已经加入得到的 [sessionId]。
 */
data class RelayConfig(
    val enabled: Boolean = false,
    val baseUrl: String = DEFAULT_HUB_BASE,
    val token: String = "",
    val tokenUnreadable: Boolean = false,
    val alias: String = DEFAULT_RELAY_ALIAS,
    val agentType: String = DEFAULT_RELAY_AGENT_TYPE,
    val joinCode: String = "",
    val sessionId: String = "",
    val uploadScreenshots: Boolean = false,
)

/** worker 加入时必须交的本机环境。不要填账户名、机器名或别的机器的信息。 */
data class RelayEnvironment(
    val osName: String,
    val osVersion: String,
    val architecture: String,
) {
    init {
        require(osName.isNotBlank() && osVersion.isNotBlank() && architecture.isNotBlank()) {
            "environment incomplete"
        }
    }
}

data class RelayMessage(val requestId: String, val kind: String, val text: String)

/**
 * Hub 拒绝或本地还不能发。[retryable] 为假时换正文再试也没有用(配置、401、409)。
 * [message] 已去掉令牌和形如密钥的串,可以给界面看。
 */
class RelayException(val status: Int, val retryable: Boolean, message: String) : IOException(message)

/** 执行端用到的 Hub Relay 调用。实现不得创建会话。 */
interface RelayTransport {
    fun connect(baseUrl: String, alias: String, agentType: String): String
    fun join(baseUrl: String, token: String, code: String, environment: RelayEnvironment): String
    fun send(
        baseUrl: String, token: String, sessionId: String, kind: String, text: String, requestId: String,
        attachments: List<String> = emptyList(),
    ): Long?

    fun upload(baseUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray): String
    fun heartbeat(baseUrl: String, token: String, sessionId: String)
}
