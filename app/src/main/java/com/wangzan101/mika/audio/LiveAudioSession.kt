package com.wangzan101.mika.audio

import android.content.Context
import android.content.Intent
import com.wangzan101.mika.core.cap.phone.LiveAudioPort
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

/** In-memory only. No audio or transcript files. One explicitly authorized source, one consuming task. */
object LiveAudioSession {
    data class Segment(val id: Long, val time: Long, val text: String)
    private val segments = ArrayDeque<Segment>()
    private var next = 0L
    private var owner: String? = null
    internal var generation = 0L; private set
    private var gapAt = 0L
    @Volatile var running = false; private set
    @Volatile var status = "尚未开始监听"; private set
    @Volatile var source = "internal"; private set
    @Volatile var lastRead = 0L; private set
    private var dropped = 0
    private var lastError: String? = null

    @Synchronized fun begin(microphone: Boolean) {
        segments.clear(); next = 0; owner = null; generation++
        dropped = 0; gapAt = 0; lastError = null
        source = if (microphone) "microphone" else "internal"
        running = true; status = "正在监听"; lastRead = System.currentTimeMillis()
    }
    @Synchronized fun preview(): String = segments.lastOrNull()?.text.orEmpty()
    @Synchronized fun end(message: String = "监听已停止") { running = false; status = message; segments.clear() }
    @Synchronized fun error(message: String) { lastError = message; status = message }
    @Synchronized fun gap() { dropped++; gapAt = next + 1; lastError = "识别积压，部分音频已丢弃" }
    @Synchronized fun add(time: Long, text: String) {
        if (!running) return
        lastError = null; status = "正在监听"
        if (text.isBlank()) return
        segments.addLast(Segment(++next, time, text.take(6000)))
        while (segments.size > 60) segments.removeFirst()
    }
    @Synchronized internal fun snapshot(task: String, after: Long, expected: Long): JsonObject = buildJsonObject {
        if (owner != null && owner != task) {
            put("ok", false); put("status", "busy"); put("message", "监听已由另一个任务使用"); return@buildJsonObject
        }
        if (expected != generation) {
            put("ok", false); put("status", "session_changed"); return@buildJsonObject
        }
        if (running) { owner = task; lastRead = System.currentTimeMillis() }
        put("ok", running && lastError == null)
        put("status", if (!running) "not_listening" else if (lastError != null) "error" else "listening")
        put("message", status); put("source", source); put("dropped", dropped)
        val first = segments.firstOrNull()?.id ?: (next + 1)
        put("gap", after < gapAt || after < first - 1)
        put("cursor", next)
        put("now", System.currentTimeMillis())
        putJsonArray("segments") {
            if (running) segments.filter { it.id > after && System.currentTimeMillis() - it.time < 45_000 }.forEach {
                addJsonObject { put("id", it.id); put("capturedAt", it.time); put("text", com.wangzan101.mika.core.observation.Untrusted.wrap(it.text)) }
            }
        }
    }
    fun port(context: Context, task: String): LiveAudioPort {
        val app = context.applicationContext
        return object : LiveAudioPort {
            private var session: Long? = null
            override suspend fun read(after: Long, waitSeconds: Int): JsonObject {
                val version = session ?: synchronized(this@LiveAudioSession) { generation }.also { if (running) session = it }
                val until = System.currentTimeMillis() + waitSeconds * 1000L
                while (true) {
                    val result = snapshot(task, after, version)
                    if (result["status"]?.jsonPrimitive?.content != "listening" ||
                        result["segments"]?.jsonArray?.isNotEmpty() == true || System.currentTimeMillis() >= until) return result
                    delay(250)
                }
            }
            override fun stop(): Boolean = stopFor(app, task)
        }
    }
    @Synchronized private fun stopFor(context: Context, task: String): Boolean {
        if (owner != null && owner != task) return false
        end(); context.stopService(Intent(context, LiveAudioService::class.java))
        return true
    }
    @Synchronized fun release(context: Context, task: String) {
        if (owner != task) return
        end(); context.stopService(Intent(context, LiveAudioService::class.java))
    }
}
