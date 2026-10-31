package com.wangzan101.mika.audio

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OpenAI-compatible multipart transcription protocol. Never includes a server error body in logs/UI. */
class LiveTranscriber(private val endpoint: String, private val key: String, private val model: String) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(25, TimeUnit.SECONDS).build()
    suspend fun transcribe(pcm: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model).addFormDataPart("response_format", "json")
            .addFormDataPart("file", "speech.wav", wav(pcm).toRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url(endpoint).header("Authorization", "Bearer $key").post(body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("语音识别网络失败"))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val result = runCatching {
                            check(it.isSuccessful) { "语音识别接口返回 HTTP ${it.code}" }
                            val source = it.body?.source() ?: error("识别结果为空")
                            source.request(64 * 1024L + 1)
                            check(source.buffer.size <= 64 * 1024) { "识别结果过大" }
                            Json.parseToJsonElement(source.readUtf8()).jsonObject["text"]?.jsonPrimitive?.content
                                ?: error("识别结果缺少 text 字段")
                        }
                        if (continuation.isActive) result.fold(continuation::resume, continuation::resumeWithException)
                    }
                }
            })
        }
    }
    companion object {
        fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
}
