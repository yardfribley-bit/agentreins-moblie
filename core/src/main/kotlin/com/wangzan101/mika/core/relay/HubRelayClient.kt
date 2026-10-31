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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Hub Relay 的 HTTP 调用。worker 只 `join`、发 `progress` / `result`、上传附件、心跳。
 * 没有创建会话的方法:会话由 Hub 上的管理者建好,手机凭 Topic 码或一次性邀请加入。
 */
class HubRelayClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) : RelayTransport {
    private val json = Json { ignoreUnknownKeys = true }

    override fun connect(baseUrl: String, alias: String, agentType: String): String {
        val body = buildJsonObject {
            put("alias", alias)
            put("agent_type", agentType)
        }.toString()
        val text = post(url(baseUrl, "v1", "connect"), token = null, payload = body, secret = null)
        return text.obj()["token"].str() ?: throw RelayException(200, false, "missing token")
    }

    override fun join(baseUrl: String, token: String, code: String, environment: RelayEnvironment): String {
        val body = buildJsonObject {
            put("code", code)
            put("role", "worker")
            put("environment", buildJsonObject {
                put("os_name", environment.osName)
                put("os_version", environment.osVersion)
                put("architecture", environment.architecture)
            })
        }.toString()
        val text = post(url(baseUrl, "v1", "relay", "join"), token, body, secret = token)
        val root = text.obj()
        val id = root["session"].obj()?.get("id").str() ?: root["session_id"].str()
        return id?.takeIf { it.isNotBlank() } ?: throw RelayException(200, false, "missing session id")
    }

    override fun send(
        baseUrl: String, token: String, sessionId: String, kind: String, text: String, requestId: String,
        attachments: List<String>,
    ): Long? {
        if (kind != RelayKind.PROGRESS && kind != RelayKind.RESULT) {
            throw RelayException(0, false, "worker kind only")
        }
        val clipped = RelayEventText.clip(text)
        if (clipped.isBlank() || requestId.isBlank()) throw RelayException(0, false, "empty message")
        val body = buildJsonObject {
            put("kind", kind)
            put("text", clipped)
            put("request_id", requestId)
            if (attachments.isNotEmpty()) put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        }.toString()
        val raw = post(url(baseUrl, "v1", "relay", "sessions", sessionId, "messages"), token, body, secret = token)
        return seqOf(raw)
    }

    override fun upload(baseUrl: String, token: String, sessionId: String, name: String, bytes: ByteArray): String {
        val target = url(baseUrl, "v1", "relay", "sessions", sessionId, "files", query = "name" to name)
        val request = Request.Builder().url(target).header("Authorization", "Bearer $token")
            .post(bytes.toRequestBody(BYTES)).build()
        val raw = execute(request, token)
        val root = raw.obj()
        return root["file_id"].str() ?: root["id"].str() ?: throw RelayException(200, false, "missing file id")
    }

    override fun heartbeat(baseUrl: String, token: String, sessionId: String) {
        post(url(baseUrl, "v1", "relay", "sessions", sessionId, "heartbeat"), token, "{}", secret = token)
    }

    private fun post(url: HttpUrl, token: String?, payload: String, secret: String?): String {
        val builder = Request.Builder().url(url).post(payload.toRequestBody(JSON))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        return execute(builder.build(), secret)
    }

    private fun execute(request: Request, secret: String?): String {
        val response = try {
            http.newCall(request).execute()
        } catch (e: RelayException) {
            throw e
        } catch (e: java.io.IOException) {
            throw e
        }
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw httpError(resp.code, raw, secret)
            return raw
        }
    }

    private fun httpError(code: Int, body: String, secret: String?): RelayException {
        val root = runCatching { body.obj() }.getOrNull()
        val retryable = when (code) {
            400, 401, 403, 404, 409 -> false
            else -> root?.get("retryable").bool() ?: (code == 408 || code == 429 || code >= 500)
        }
        val err = root?.get("error").str()?.take(200)
        var message = if (err.isNullOrBlank()) "HTTP $code" else "HTTP $code: $err"
        message = Redactor.text(message)
        if (!secret.isNullOrEmpty()) message = message.replace(secret, Redactor.MASK)
        return RelayException(code, retryable, message)
    }

    private fun seqOf(raw: String): Long? {
        val root = runCatching { raw.obj() }.getOrNull() ?: return null
        return root["seq"].long() ?: root["event"].obj()?.get("seq").long() ?: root["message"].obj()?.get("seq").long()
    }

    private fun String.obj(): JsonObject = json.parseToJsonElement(this) as? JsonObject ?: JsonObject(emptyMap())

    private fun url(base: String, vararg segments: String, query: Pair<String, String>? = null): HttpUrl {
        val root = try {
            base.trim().trimEnd('/').ifBlank { DEFAULT_HUB_BASE }.toHttpUrl()
        } catch (e: IllegalArgumentException) {
            throw RelayException(0, false, "bad hub url")
        }
        val builder = root.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        if (query != null) builder.addQueryParameter(query.first, query.second)
        return builder.build()
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
    private fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val BYTES = "application/octet-stream".toMediaType()
    }
}
