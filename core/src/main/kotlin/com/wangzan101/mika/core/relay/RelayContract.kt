package com.wangzan101.mika.core.relay

import java.io.IOException

/**
 * Hub 站点根。不预填任何个人或作者的地址：没填写时为空，由用户在设置里填自己的 Hub。
 * MCP 地址由 [mcpEndpoint] 从用户填的站点根或完整 URL 推出来。
 */
const val DEFAULT_HUB_BASE = ""

/** 和其他外部智能体一样走的 Streamable HTTP MCP。空白表示还没配置。 */
const val DEFAULT_MCP_URL = ""

/**
 * 设置里可以填 MCP 全址,也可以填站点根(旧的 Hub 地址)。
 * `https://host/task` 和 `https://host/task/v1/mcp` 都落到同一个端点。
 */
fun mcpEndpoint(raw: String): String {
    val base = raw.trim().trimEnd('/')
    if (base.isBlank()) return ""
    return when {
        base.endsWith("/mcp") -> base
        base.endsWith("/v1") -> "$base/mcp"
        else -> "$base/v1/mcp"
    }
}

const val DEFAULT_RELAY_ALIAS = "x-assistant"
const val DEFAULT_RELAY_AGENT_TYPE = "x-assistant"

/** 发给 Hub 的正文上限(协议)。 */
const val RELAY_MAX_TEXT = 8000

/** 截图默认不上传;打开后超过这个字节数的画面只记一句,不传字节。 */
const val MAX_SCREENSHOT_BYTES = 1_500_000

/**
 * 老板指令附件的上限。Hub 本身允许 100MB；手机上只收下 32MB，再大的记成缺失，不把字节读进内存。
 * MCP 内联（`content_base64`）的分界是 5MB，更大的走签名链接，两边都用这个上限卡住。
 */
const val HUB_ATTACHMENT_MAX_BYTES = 32L * 1024 * 1024

/** 一条指令最多带的附件个数，和 Hub 的 `MAX_ATTACHMENTS` 一样。 */
const val HUB_ATTACHMENT_MAX_FILES = 20

object RelayKind {
    const val PROGRESS = "progress"
    const val RESULT = "result"
}

/**
 * 这一台手机要对齐的 Hub。令牌由调用方保管;[token] 为空且 [tokenUnreadable] 为假时,
 * 才用 [alias] / [agentType] 走一次 MCP 工具 `diaodu_connect`。
 * 手机不创建会话:只有 [joinCode](Topic 码或一次性 worker 邀请)或已经加入得到的 [sessionId]。
 * [claimTasks] 默认关。打开也不执行远程任务,只是把以后的认领接口留出来。
 */
data class RelayConfig(
    val enabled: Boolean = false,
    val mcpUrl: String = DEFAULT_MCP_URL,
    val token: String = "",
    val tokenUnreadable: Boolean = false,
    val alias: String = DEFAULT_RELAY_ALIAS,
    val agentType: String = DEFAULT_RELAY_AGENT_TYPE,
    val joinCode: String = "",
    val sessionId: String = "",
    val agentId: String = "",
    val uploadScreenshots: Boolean = false,
    val claimTasks: Boolean = false,
    val runOwnerInstructions: Boolean = true,
)

/** `diaodu_connect` 只返回一次令牌。[agentId] 有就记下;协议里不一定带。 */
data class RelayIdentity(val token: String, val agentId: String? = null)

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

/**
 * 执行端用到的 Hub 调用,经 MCP 工具而不是直接打 Relay 的 HTTP 路径。
 * 实现不得创建会话(`diaodu_relay_create` 不在这里)。
 */
interface RelayTransport {
    fun connect(mcpUrl: String, alias: String, agentType: String): RelayIdentity
    fun join(mcpUrl: String, token: String, code: String, environment: RelayEnvironment): String
    fun send(
        mcpUrl: String, token: String, sessionId: String, kind: String, text: String, requestId: String,
        attachments: List<String> = emptyList(),
        replyTo: Long? = null,
    ): Long?

    fun upload(mcpUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray): String

    /**
     * `diaodu_relay_download`（Hub `diaodu/relay_mcp.py` 的 `relay_download`）。
     * 参数是 `session` 和 `file_id`。响应带 `file_id`、`name`、`size`、`sha256`、`type`。
     * 不超过 5MB 时还有 `content_base64`（放在 [RelayFileDownload.bytes]）；更大则 `too_large`
     * 为真，只给 `download_url`（签名 15 分钟），字节用 [fetchUnsigned] 去取，不要加 Authorization。
     */
    fun download(mcpUrl: String, token: String, sessionId: String, fileId: String): RelayFileDownload

    /**
     * 下载 [download] 返回的签名链接。不要加 Authorization，也不要把链接写进日志。
     * 读到的字节超过 [maxBytes] 时失败，不返回半截。
     */
    fun fetchUnsigned(url: String, maxBytes: Long): ByteArray

    fun heartbeat(mcpUrl: String, token: String, sessionId: String, environment: RelayEnvironment)
    fun read(mcpUrl: String, token: String, sessionId: String, after: Long, waitSeconds: Int): HubRelayPage

    /** `diaodu_relay_ack`:已收到直到 [seq]。待确认计数靠它,不代表已经做完。 */
    fun ack(mcpUrl: String, token: String, sessionId: String, seq: Long)

    /** 用已有 Bearer 找回 agent_id,不签发新令牌。 */
    fun whoami(mcpUrl: String, token: String): String?

    /** 传入 session 时:仍是成员为 true。`member=false` 要重新加入。 */
    fun memberOf(mcpUrl: String, token: String, sessionId: String): Boolean
}

/**
 * `diaodu_relay_download` 的一页元数据。字节只在不超过 5MB 时有；更大只有 [downloadUrl]。
 * [toString] 不带字节、也不带签名链接。
 */
class RelayFileDownload(
    val fileId: String,
    val name: String,
    val size: Long,
    val sha256: String?,
    val mime: String,
    val bytes: ByteArray?,
    val downloadUrl: String?,
) {
    override fun toString(): String =
        "RelayFileDownload(fileId=$fileId, size=$size, mime=$mime, inline=${bytes != null}, remote=${!downloadUrl.isNullOrBlank()})"
}
