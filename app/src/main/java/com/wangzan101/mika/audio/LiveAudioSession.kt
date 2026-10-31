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
    private var startedAt = 0L
    private var dropped = 0
    private var lastError: String? = null
    private var blankNoted = false
    private var reason = LiveAudioWatch.NEVER_STARTED

    @Synchronized fun begin(microphone: Boolean) {
        segments.clear(); next = 0; owner = null; generation++
        dropped = 0; gapAt = 0; lastError = null; blankNoted = false
        source = if (microphone) "microphone" else "internal"
        running = true; status = "正在监听"
        startedAt = System.currentTimeMillis(); lastRead = startedAt
    }
    /** Same session after internal capture stayed silent. Does not bump generation or clear the cursor. */
    @Synchronized fun adoptMicrophone(message: String) {
        if (!running) return
        source = "microphone"
        lastError = null
        status = message
    }
    @Synchronized fun preview(): String = segments.lastOrNull()?.text.orEmpty()
    @Synchronized fun end(message: String = "监听已停止", why: String = LiveAudioWatch.USER_STOPPED) {
        running = false; owner = null; status = message; reason = why; segments.clear()
    }
    /** False while capture should continue. Stops on the one-hour cap, or on two minutes without a read after a task has claimed the session. */
    @Synchronized fun applyWatch(now: Long): Boolean {
        if (!running) return false
        val why = LiveAudioWatch.limit(now, startedAt, lastRead, owner != null) ?: return false
        end(LiveAudioWatch.message(why), why)
        return true
    }
    @Synchronized fun error(message: String) { lastError = message; status = message }
    @Synchronized fun gap() { dropped++; gapAt = next + 1; lastError = "识别积压，部分音频已丢弃" }
    @Synchronized fun add(time: Long, text: String) {
        if (!running) return
        if (text.isBlank()) {
            lastError = null
            // Once per quiet streak. Leave the auto-mic status up so the switch stays visible.
            if (!blankNoted && status != LiveAudioFallback.FALLBACK_STATUS) {
                blankNoted = true
                status = "已采集到声音，但这次没有识别出文字"
            }
            return
        }
        lastError = null
        blankNoted = false
        status = "正在监听"
        segments.addLast(Segment(++next, time, text.take(6000)))
        while (segments.size > 60) segments.removeFirst()
    }
    @Synchronized internal fun snapshot(task: String, after: Long, expected: Long): JsonObject = buildJsonObject {
        if (owner != null && owner != task) {
            put("ok", false); put("status", "busy"); put("message", "监听已由另一个任务使用"); return@buildJsonObject
        }
        if (expected != generation) {
            put("ok", false); put("status", "session_changed")
            put("message", "上一场监听已结束。再读一次即可接上用户重新开始的这场，不要说工具无法重新开启。")
            return@buildJsonObject
        }
        if (running) { owner = task; lastRead = System.currentTimeMillis() }
        put("ok", running && lastError == null)
        put("status", if (!running) "not_listening" else if (lastError != null) "error" else "listening")
        put("message", status); put("source", source); put("dropped", dropped)
        if (!running) put("reason", reason)
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
        return openReader(task) { stopFor(app, task) }
    }
    /**
     * One task run's view of the single session. The first read while capture is running
     * latches that generation so the cursor stays paired with it. [begin] bumps generation
     * on every Settings restart; a latched reader must follow, and must not apply the old
     * cursor (segment ids start over, so `id > after` would skip the new speech).
     */
    internal fun openReader(task: String, halt: () -> Boolean): LiveAudioPort = object : LiveAudioPort {
        private var bound: Long? = null
        override suspend fun read(after: Long, waitSeconds: Int): JsonObject {
            var cursor = after
            var changed = false
            val until = System.currentTimeMillis() + waitSeconds * 1000L
            while (true) {
                val version = synchronized(this@LiveAudioSession) {
                    val current = generation
                    if (bound != null && bound != current) {
                        bound = current
                        cursor = 0
                    } else if (bound == null && running) {
                        bound = current
                    }
                    bound ?: current
                }
                val result = snapshot(task, cursor, version)
                val status = result["status"]?.jsonPrimitive?.content
                if (status == "session_changed" && !changed) {
                    changed = true
                    if (System.currentTimeMillis() < until) delay(250)
                    continue
                }
                if (status != "listening" || result["segments"]?.jsonArray?.isNotEmpty() == true || System.currentTimeMillis() >= until) return result
                delay(250)
            }
        }
        override fun stop(): Boolean = halt()
    }
    @Synchronized private fun stopFor(context: Context, task: String): Boolean {
        if (owner != null && owner != task) return false
        end("你已停止监听", LiveAudioWatch.USER_STOPPED)
        context.stopService(Intent(context, LiveAudioService::class.java))
        return true
    }
    @Synchronized fun release(context: Context, task: String) {
        if (owner != task) return
        end("任务已结束，监听已停止", LiveAudioWatch.TASK_ENDED)
        context.stopService(Intent(context, LiveAudioService::class.java))
    }
}
