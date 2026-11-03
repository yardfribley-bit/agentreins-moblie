package com.wangzan101.mika.audio

import com.wangzan101.mika.core.cap.phone.AsrConfig
import com.wangzan101.mika.core.cap.phone.speech.SpeechConfig
import com.wangzan101.mika.core.cap.phone.speech.SpeechPresets
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** OpenAI-compatible /audio/speech. PCM bytes are delivered as they arrive. The key is never logged. */
internal class SpeechHttp(val code: Int, message: String) : IOException(message)

internal class OnlineSpeechClient(
    private val endpoint: String,
    private val key: String,
    private val model: String,
    private val voice: String,
) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val call = AtomicReference<Call?>(null)

    fun stream(text: String, speed: Float, style: Boolean, aborted: () -> Boolean, onPcm: (ByteArray) -> Unit) {
        val body = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "pcm")
            put("speed", speed.toDouble())
            if (style && SpeechConfig.sendsStyle(model)) put("instructions", SpeechPresets.CHINESE_STYLE)
        }.toString()
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Bearer $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val started = client.newCall(request)
        call.set(started)
        try {
            started.execute().use { response ->
                if (!response.isSuccessful) {
                    val raw = response.body?.string().orEmpty().take(500)
                    throw httpFailure(response.code, raw)
                }
                val source = response.body?.source() ?: throw SpeechHttp(0, "语音服务没有返回音频")
                val buf = ByteArray(3840)
                while (!aborted()) {
                    val n = source.read(buf)
                    if (n < 0) break
                    if (n > 0) onPcm(buf.copyOf(n))
                }
            }
        } finally {
            call.compareAndSet(started, null)
        }
    }

    fun cancel() { call.get()?.cancel() }

    private fun httpFailure(code: Int, body: String): SpeechHttp {
        val mismatch = if (code == 401) AsrConfig.mismatch(endpoint, key) else null
        val hint = when {
            mismatch?.keyKind == "openrouter" -> "这个 Key 以 sk-or- 开头，属于 OpenRouter，但接口地址是 OpenAI"
            mismatch?.keyKind == "openai" -> "这个 Key 看起来属于 OpenAI，但接口地址是 OpenRouter"
            code == 401 -> "Key 无效或已失效"
            code == 402 -> "账户余额不足"
            code == 400 -> "请求被拒绝，请检查模型、音色和语速"
            code == 404 -> "接口或模型不存在"
            code == 429 -> "请求过于频繁或额度受限"
            else -> "语音合成失败（HTTP $code）"
        }
        val message = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(body).let { parsed ->
                (parsed as? kotlinx.serialization.json.JsonObject)?.get("error")
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("message")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content
            }
        }.getOrNull().orEmpty()
        val redacted = (if (key.isNotEmpty()) message.replace(key, "[redacted]") else message)
            .replace(Regex("sk-[A-Za-z0-9_\\-]+"), "[redacted]")
            .replace(Regex("[\\r\\n]+"), " ")
            .take(180)
        val detail = if (redacted.isBlank()) hint else "$hint：$redacted"
        return SpeechHttp(code, detail)
    }
}
