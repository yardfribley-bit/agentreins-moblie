package com.wangzan101.mika.audio

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import javax.net.ssl.SSLException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OpenAI-compatible multipart transcription protocol. Never includes a server error body in logs/UI. */
internal class SpeechFailure(message: String, val retryable: Boolean = false) : IOException(message)

class LiveTranscriber(private val endpoint: String, private val key: String, private val model: String) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()
    suspend fun transcribe(pcm: ByteArray): String {
        try { return request(pcm) }
        catch (e: SpeechFailure) {
            if (!e.retryable) throw e
            delay(1000)
            return request(pcm)
        }
    }
    private suspend fun request(pcm: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = runCatching {
                            if (!it.isSuccessful) {
                                val source = it.body?.source()
                                val raw = source?.let { bodySource ->
                                    bodySource.request(4096)
                                    bodySource.buffer.clone().readUtf8(minOf(bodySource.buffer.size, 4096L))
                                }.orEmpty()
                                throw httpFailure(it.code, raw, key, endpoint)
                            }
                            val source = it.body?.source() ?: error("识别结果为空")
                            source.request(64 * 1024L + 1)
                            check(source.buffer.size <= 64 * 1024) { "识别结果过大" }
                            val parsed = runCatching { Json.parseToJsonElement(source.readUtf8()).jsonObject }.getOrNull()
                                ?: throw SpeechFailure("ASR_RESPONSE：服务未返回有效 JSON，请检查接口地址")
                            parsed["text"]?.jsonPrimitive?.content
                                ?: throw SpeechFailure("ASR_RESPONSE：识别结果缺少 text 字段，请检查语音模型和接口协议")
                        }
                        if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                    }
                }
            })
        }
    }
    companion object {
        internal fun networkFailure(e: IOException): SpeechFailure = when (e) {
            is UnknownHostException -> SpeechFailure("ASR_DNS：无法解析服务域名，请检查手机网络和 DNS")
            is SocketTimeoutException -> SpeechFailure("ASR_TIMEOUT：连接或响应超时，请确认手机可以访问语音服务", true)
            is InterruptedIOException -> SpeechFailure("ASR_TIMEOUT：请求超过时限，请确认手机可以访问语音服务", true)
            is SSLException -> SpeechFailure("ASR_TLS：HTTPS 证书校验失败，请检查手机时间、证书或网络代理")
            is ConnectException -> SpeechFailure("ASR_CONNECT：无法连接语音服务，请检查手机网络或代理", true)
            else -> SpeechFailure("ASR_NETWORK：网络连接中断，请检查手机能否访问语音服务", true)
        }
        internal fun httpFailure(code: Int, body: String, key: String, endpoint: String = ""): SpeechFailure {
            val mismatch = if (code == 401) com.wangzan101.mika.core.cap.phone.AsrConfig.mismatch(endpoint, key) else null
            val hint = when {
                mismatch?.keyKind == "openrouter" -> "这个 Key 以 sk-or- 开头，属于 OpenRouter，但接口地址是 OpenAI。请改用 OpenRouter 预设"
                mismatch?.keyKind == "openai" -> "这个 Key 看起来属于 OpenAI，但接口地址是 OpenRouter。请改用 OpenAI 预设"
                code == 401 -> "Key 无效或已失效，请重新填写语音识别 Key"
                code == 402 -> "账户余额不足，请检查 OpenRouter 余额"
                code == 403 -> "服务拒绝访问，请检查模型的地区限制和 Key 权限"
                code == 404 -> "接口或模型不存在，请检查完整接口地址及模型名"
                code == 429 -> "请求过于频繁或额度受限，请稍后重试"
                code in 500..599 -> "语音服务暂时异常，请稍后重试"
                code in 300..399 -> "接口发生重定向，请填写最终的 HTTPS 语音识别地址"
                else -> "请求被拒绝，请检查语音模型和接口配置"
            }
            val message = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val redacted = (if (key.isNotEmpty()) message.replace(key, "[redacted]") else message)
                .replace(Regex("sk-[A-Za-z0-9_-]+"), "[redacted]")
                .replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer [redacted]")
                .replace(Regex("[\\r\\n]+"), " ").take(240)
            return SpeechFailure("HTTP $code：$hint" + if (redacted.isBlank()) "" else "\n服务提示：$redacted", code == 429 || code in 500..599)
        }
        fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
