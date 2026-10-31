package com.wangzan101.mika.core.relay

import com.wangzan101.mika.core.record.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hub 的 Streamable HTTP MCP 客户端:JSON-RPC 2.0,`initialize` 之后带 `Mcp-Session-Id`。
 * 进度和结果走 `diaodu_relay_send`,不直接请求 Relay HTTP,也不调用 `diaodu_relay_create`。
 * 响应可以是一条 JSON,也可以是 `text/event-stream` 里的 `data:`。
 */
class HubMcpClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        .build(),
    private val mcpUploadMaxBytes: Int = MCP_UPLOAD_MAX,
) : RelayTransport {
    private val json = Json { ignoreUnknownKeys = true }
    private val ids = AtomicInteger(0)
    private val sessionLock = Any()
    private var mcpSession: String? = null

    override fun connect(mcpUrl: String, alias: String, agentType: String): RelayIdentity {
        val got = call(mcpUrl, token = null, "diaodu_connect", buildJsonObject {
            put("alias", alias)
            put("agent_type", agentType)
        })
        val body = got.obj()
        val token = body.str("token") ?: throw RelayException(200, false, "missing token")
        val agentId = body.str("agent_id")
        synchronized(sessionLock) { mcpSession = null }
        return RelayIdentity(token, agentId)
    }

    override fun whoami(mcpUrl: String, token: String): String? =
        call(mcpUrl, token, "diaodu_whoami", buildJsonObject {}, secret = token).obj().str("agent_id")

    override fun memberOf(mcpUrl: String, token: String, sessionId: String): Boolean {
        val body = call(mcpUrl, token, "diaodu_relay_list", buildJsonObject {
            put("session", sessionId)
        }, secret = token).obj() ?: return false
        body["member"]?.bool()?.let { return it }
        val sessions = body["sessions"] as? JsonArray ?: return false
        return sessions.any { (it as? JsonObject).str("id") == sessionId }
    }

    override fun ack(mcpUrl: String, token: String, sessionId: String, seq: Long) {
        call(mcpUrl, token, "diaodu_relay_ack", buildJsonObject {
            put("session", sessionId)
            put("seq", seq)
        }, secret = token)
    }

    /** 读取一页事件。`wait` 不超过 20 秒。 */
    override fun read(mcpUrl: String, token: String, sessionId: String, after: Long, waitSeconds: Int): HubRelayPage {
        val body = call(mcpUrl, token, "diaodu_relay_read", buildJsonObject {
            put("session", sessionId)
            put("after", after)
            put("wait", HubInstructionFilter.waitSeconds(waitSeconds))
        }, secret = token).obj() ?: return HubRelayPage(emptyList(), after, false)
        return HubInstructionFilter.parse(body)
    }

    override fun join(mcpUrl: String, token: String, code: String, environment: RelayEnvironment): String {
        val body = call(mcpUrl, token, "diaodu_relay_join", buildJsonObject {
            put("code", code)
            put("role", "worker")
            put("environment", environmentJson(environment))
        }, secret = token).obj()
        val id = body?.get("session").obj()?.str("id") ?: body.str("session_id")
        return id?.takeIf { it.isNotBlank() } ?: throw RelayException(200, false, "missing session id")
    }

    override fun send(
        mcpUrl: String, token: String, sessionId: String, kind: String, text: String, requestId: String,
        attachments: List<String>,
        replyTo: Long?,
    ): Long? {
        if (kind != RelayKind.PROGRESS && kind != RelayKind.RESULT) {
            throw RelayException(0, false, "worker kind only")
        }
        val clipped = RelayEventText.clip(text)
        if (clipped.isBlank() || requestId.isBlank()) throw RelayException(0, false, "empty message")
        val body = call(mcpUrl, token, "diaodu_relay_send", buildJsonObject {
            put("session", sessionId)
            put("kind", kind)
            put("text", clipped)
            put("request_id", requestId)
            if (replyTo != null && replyTo > 0) put("reply_to", replyTo)
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        }, secret = token).obj()
        return seqOf(body)
    }

    override fun upload(mcpUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray): String {
        if (bytes.size > mcpUploadMaxBytes) return uploadRest(mcpUrl, token, sessionId, name, bytes)
        val body = call(mcpUrl, token, "diaodu_relay_upload", buildJsonObject {
            put("session", sessionId)
            put("name", name)
            put("content_base64", Base64.getEncoder().encodeToString(bytes))
        }, secret = token).obj()
        return body.str("file_id") ?: body.str("id") ?: throw RelayException(200, false, "missing file id")
    }

    private fun uploadRest(mcpUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray): String {
        val url = relayFilesUrl(mcpUrl, sessionId, name)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .post(bytes.toRequestBody(BYTES)).build()
        val response = http.newCall(request).execute()
        val raw = response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp.code, token)
            text
        }
        val body = payload(raw)
        return body.str("file_id") ?: body.str("id") ?: throw RelayException(200, false, "missing file id")
    }

    override fun heartbeat(mcpUrl: String, token: String, sessionId: String, environment: RelayEnvironment) {
        call(mcpUrl, token, "diaodu_relay_heartbeat", buildJsonObject {
            put("session", sessionId)
            put("environment", environmentJson(environment))
        }, secret = token)
    }

    /** 握手之后列工具。镜像本身不靠它发进度。 */
    fun listTools(mcpUrl: String, token: String?): List<String> {
        val raw = rpc(mcpUrl, token, rpc("tools/list", buildJsonObject {}), secret = token)
        val result = rpcResult(raw, token)
        val tools = result?.get("tools") as? JsonArray ?: return emptyList()
        return tools.mapNotNull { (it as? JsonObject).str("name") }
    }

    private fun call(mcpUrl: String, token: String?, tool: String, arguments: JsonObject, secret: String? = token): ToolPayload {
        val raw = rpc(mcpUrl, token, rpc("tools/call", buildJsonObject {
            put("name", tool)
            put("arguments", arguments)
        }), secret)
        val result = rpcResult(raw, secret) ?: throw RelayException(200, false, "empty tool result")
        val text = (result["content"] as? JsonArray).orEmpty().mapNotNull { item ->
            (item as? JsonObject)?.get("text").str()
        }.joinToString("\n")
        if (result["isError"]?.bool() == true) throw toolError(text, secret)
        val structured = result["structuredContent"] as? JsonObject
        return ToolPayload(structured, text)
    }

    private fun rpc(mcpUrl: String, token: String?, body: String, secret: String?): String {
        var retried = false
        while (true) {
            try {
                return post(mcpUrl, token, body, secret)
            } catch (e: RelayException) {
                if (!retried && e.status == 404) {
                    retried = true
                    synchronized(sessionLock) { mcpSession = null }
                    continue
                }
                throw e
            }
        }
    }

    private fun post(mcpUrl: String, token: String?, body: String, secret: String?): String {
        ensureSession(mcpUrl, token, secret)
        val session = synchronized(sessionLock) { mcpSession }
        val response = http.newCall(request(mcpUrl, token, body, session)).execute()
        response.use { resp ->
            val raw = messageBody(resp.header("content-type"), resp.body?.string().orEmpty())
            if (resp.code == 404) throw RelayException(404, false, "HTTP 404")
            if (!resp.isSuccessful && resp.code != 202) throw httpError(resp.code, secret)
            rememberSession(resp.header("mcp-session-id") ?: resp.header("Mcp-Session-Id"))
            return raw
        }
    }

    private fun ensureSession(mcpUrl: String, token: String?, secret: String?) {
        if (synchronized(sessionLock) { mcpSession } != null) return
        synchronized(sessionLock) {
            if (mcpSession != null) return
            val init = rpc("initialize", buildJsonObject {
                put("protocolVersion", PROTOCOL)
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject {
                    put("name", "agentreins-moblie")
                    put("version", "0.1")
                })
            })
            val response = http.newCall(request(mcpUrl, token, init, session = null)).execute()
            val sid = response.use { resp ->
                resp.body?.string()
                if (!resp.isSuccessful && resp.code != 202) throw httpError(resp.code, secret)
                resp.header("mcp-session-id") ?: resp.header("Mcp-Session-Id")
            } ?: throw RelayException(200, false, "missing mcp session")
            mcpSession = sid
            val note = Request.Builder().url(mcpEndpoint(mcpUrl)).post(notificationBody())
            note.header("mcp-session-id", sid)
            note.header("MCP-Protocol-Version", PROTOCOL)
            note.header("Accept", ACCEPT)
            if (!token.isNullOrBlank()) note.header("Authorization", "Bearer $token")
            http.newCall(note.build()).execute().use { resp ->
                resp.body?.close()
                if (!resp.isSuccessful && resp.code != 202) {
                    mcpSession = null
                    throw httpError(resp.code, secret)
                }
            }
        }
    }

    private fun request(mcpUrl: String, token: String?, body: String, session: String?): Request {
        val builder = Request.Builder().url(mcpEndpoint(mcpUrl))
            .header("Accept", ACCEPT)
            .header("MCP-Protocol-Version", PROTOCOL)
            .post(body.toRequestBody(JSON))
        if (!session.isNullOrBlank()) builder.header("mcp-session-id", session)
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return builder.build()
    }

    private fun notificationBody(): okhttp3.RequestBody =
        """{"jsonrpc":"2.0","method":"notifications/initialized"}""".toRequestBody(JSON)

    private fun rpc(method: String, params: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", ids.incrementAndGet())
        put("method", method)
        put("params", params)
    }.toString()

    private fun rpcResult(raw: String, secret: String?): JsonObject? {
        if (raw.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: throw RelayException(200, false, "bad rpc")
        val error = root["error"] as? JsonObject
        if (error != null) {
            val code = error["code"].int() ?: 0
            val message = redact(error.str("message") ?: "rpc error", secret)
            val sessionGone = message.contains("session", ignoreCase = true)
            throw RelayException(if (sessionGone) 404 else code, code == 429 || code >= 500, message)
        }
        return root["result"] as? JsonObject
    }

    private fun toolError(text: String, secret: String?): RelayException {
        val body = payload(text)
        val code = body?.get("code").int() ?: 0
        val message = redact(body.str("message") ?: text.take(200), secret)
        val retryable = code == 408 || code == 429 || code >= 500
        return RelayException(code, retryable, if (code == 0) message else "HTTP $code: $message")
    }

    private fun httpError(code: Int, secret: String?): RelayException {
        val retryable = code == 408 || code == 429 || code >= 500
        return RelayException(code, retryable, redact("HTTP $code", secret))
    }

    private fun redact(message: String, secret: String?): String {
        var out = Redactor.text(message.take(200))
        if (!secret.isNullOrEmpty()) out = out.replace(secret, Redactor.MASK)
        return out
    }

    private fun rememberSession(sid: String?) {
        if (!sid.isNullOrBlank()) synchronized(sessionLock) { mcpSession = sid }
    }

    private inner class ToolPayload(val structured: JsonObject?, val text: String) {
        /** 优先用 structuredContent,没有再解析文本里的 JSON。 */
        fun obj(): JsonObject? = structured ?: payload(text)
    }

    private fun payload(text: String): JsonObject? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val slice = if (trimmed.startsWith("{")) trimmed else {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            trimmed.substring(start, end + 1)
        }
        return runCatching { json.parseToJsonElement(slice) as? JsonObject }.getOrNull()
    }

    private fun seqOf(body: JsonObject?): Long? {
        if (body == null) return null
        body["seq"].long()?.let { return it }
        return (body["event"] as? JsonObject)?.get("seq").long()
    }

    private fun environmentJson(environment: RelayEnvironment) = buildJsonObject {
        put("os_name", environment.osName)
        put("os_version", environment.osVersion)
        put("architecture", environment.architecture)
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject?.str(key: String): String? = this?.get(key).str()
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    companion object {
        const val PROTOCOL = "2025-03-26"
        const val MCP_UPLOAD_MAX = 10 * 1024 * 1024
        private const val ACCEPT = "application/json, text/event-stream"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BYTES = "application/octet-stream".toMediaType()

        fun relayFilesUrl(mcpUrl: String, sessionId: String, name: String): okhttp3.HttpUrl {
            val root = mcpEndpoint(mcpUrl).removeSuffix("/mcp").trimEnd('/')
            return "$root/relay/sessions/$sessionId/files".toHttpUrl().newBuilder()
                .addQueryParameter("name", name).build()
        }

        /** 取 SSE 里最后一条 `data:` JSON;普通 JSON 原样返回。 */
        fun messageBody(contentType: String?, raw: String): String {
            val sse = contentType?.contains("text/event-stream") == true || raw.lineSequence().any { it.startsWith("data:") }
            if (!sse) return raw
            val data = raw.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .filter { it.isNotEmpty() && it != "[DONE]" }.lastOrNull()
            return data ?: raw
        }
    }
}
