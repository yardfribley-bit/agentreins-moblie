package com.wangzan101.mika.core.cap.phone.speech

/** How a transcript relates to audio we are playing ourselves. */
enum class Heard { Silence, Echo, Interrupt, Other }

/** Capture path the listener is using. Microphone hears our speaker; playback capture of another app does not. */
enum class CapturePath { Playback, Microphone, None }

/**
 * While our TTS is playing, and for a short tail after it stops, transcripts that match
 * what we just said are our own voice. A short owner stop command that is not part of
 * that script is an interrupt. Speech on a playback-capture path that does not match
 * the script is someone else and should yield the floor.
 */
object EchoGate {
    const val TAIL_MS = 600L

    private val commands = listOf(
        "停", "停下", "停止", "别说了", "闭嘴", "不要说了", "停止出声", "安静",
        "stop", "shutup", "bequiet", "quiet",
    )

    fun inWindow(now: Long, speakStart: Long, speakEnd: Long): Boolean {
        if (speakStart <= 0L || now < speakStart) return false
        if (speakEnd <= 0L) return true
        return now <= speakEnd + TAIL_MS
    }

    /** [speakEnd] 0 means still speaking. */
    fun overlaps(segmentStart: Long, now: Long, speakStart: Long, speakEnd: Long, speaking: Boolean): Boolean {
        if (speakStart <= 0L) return false
        val end = if (speaking || speakEnd <= 0L) now else speakEnd + TAIL_MS
        return segmentStart <= end && now >= speakStart
    }

    fun interpret(heard: String, script: String, inWindow: Boolean, path: CapturePath): Heard {
        val text = normalize(heard)
        if (text.isEmpty()) return Heard.Silence
        if (!inWindow) return Heard.Other
        val overlap = overlapRatio(text, normalize(script))
        if (isStop(text) && overlap < 0.34) return Heard.Interrupt
        if (path == CapturePath.Playback && overlap < 0.34) return Heard.Other
        return Heard.Echo
    }

    fun isStop(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isEmpty() || text.length > 12) return false
        return commands.any { text == it || text == "${it}啊" || text == "请$it" }
    }

    fun normalize(raw: String): String = buildString {
        for (c in raw.trim().lowercase()) {
            if (c.isLetterOrDigit() || c.code > 127 && !c.isWhitespace() && c !in "。！？!?；;，、,.…~～ ") append(c)
        }
    }

    /** 1 when [heard] is contained in [script]. Otherwise the share of heard characters found in the script. */
    fun overlapRatio(heard: String, script: String): Double {
        if (heard.isEmpty() || script.isEmpty()) return 0.0
        if (heard in script) return 1.0
        var found = 0
        val pool = script.toMutableList()
        for (c in heard) {
            val at = pool.indexOf(c)
            if (at >= 0) {
                found++
                pool.removeAt(at)
            }
        }
        return found.toDouble() / heard.length
    }
}

/**
 * Barge-in from energy alone. The microphone hears our speaker, so a loud mic
 * while we are talking is not someone else. Playback capture of another app
 * does not include our AudioTrack, so loud energy there is someone else.
 */
object VadYield {
    const val SPEECH_PEAK = 800

    fun shouldYield(path: CapturePath, speaking: Boolean, peak: Int): Boolean =
        speaking && path == CapturePath.Playback && peak >= SPEECH_PEAK
}
