package com.wangzan101.mika.core.cap.phone

import kotlin.math.sqrt

/**
 * One live-listen capture. Fallback to the microphone lasts only for this session.
 * The next session starts from the user's saved choice again, which retries internal capture
 * when they did not turn the microphone on themselves.
 * Segment ids start at 1 for every [begin].
 */
class LiveListenCursor {
    var sessionId: Long = 0
        private set
    var nextSegment: Long = 0
        private set

    fun begin(): Long {
        sessionId += 1
        nextSegment = 0
        return sessionId
    }

    /** Drop leftover segment ids when a listen ends, even if the process keeps the object. */
    fun end() {
        nextSegment = 0
    }

    fun allocateSegment(): Long = ++nextSegment
}

/**
 * Silence policy for one capture segment, plus the debug line written for that segment.
 * Three quiet segments (about 24s) in internal mode switch to the microphone once.
 * Microphone silence never flips back. A later listen starts a new [LiveListenCursor]
 * and does not inherit [State.fellBack].
 */
object LiveListenPolicy {
    const val LOG_TAG = "LiveAudio"
    const val SILENCE_PEAK = 120
    const val SILENT_SEGMENTS = 3
    const val FALLBACK_STATUS = "内部无声，已改用麦克风外放收音"
    const val MIC_SILENCE = "未检测到声音，请检查外放音量和麦克风"
    const val INTERNAL_SILENCE = "未采集到抖音声音：请关闭 App 静音；若仍无声，停止后改用麦克风外放收音"

    enum class Source { Internal, Microphone }

    data class State(
        val source: Source,
        val silentStreak: Int = 0,
        /** True after an automatic internal→mic attempt. Blocks a second flip in this session. */
        val fellBack: Boolean = false,
    )

    sealed class Decision {
        abstract val state: State
        abstract val name: String
        abstract val reason: String
        data class Keep(override val state: State) : Decision() {
            override val name = "keep"
            override val reason = "quiet ${state.silentStreak}/$SILENT_SEGMENTS"
        }
        data class Hear(override val state: State) : Decision() {
            override val name = "hear"
            override val reason = "peak>=$SILENCE_PEAK"
        }
        data class UseMicrophone(override val state: State, val status: String) : Decision() {
            override val name = "fallback"
            override val reason = "internal silent for $SILENT_SEGMENTS segments"
        }
        data class ReportSilence(override val state: State, val message: String) : Decision() {
            override val name = "silence"
            override val reason = message
        }
    }

    fun onSegment(state: State, peak: Int): Decision {
        if (peak >= SILENCE_PEAK) return Decision.Hear(state.copy(silentStreak = 0))
        val next = state.copy(silentStreak = state.silentStreak + 1)
        if (next.silentStreak < SILENT_SEGMENTS) return Decision.Keep(next)
        if (state.source == Source.Internal && !state.fellBack) {
            return Decision.UseMicrophone(State(Source.Microphone, silentStreak = 0, fellBack = true), FALLBACK_STATUS)
        }
        val message = if (next.source == Source.Microphone) MIC_SILENCE else INTERNAL_SILENCE
        return Decision.ReportSilence(next, message)
    }

    /**
     * The saved microphone switch is only the user's choice. An automatic fallback must not
     * change it, so the next listen retries internal capture.
     */
    fun microphoneForNextSession(savedUserPref: Boolean): Boolean = savedUserPref

    /** Root-mean-square of [count] PCM samples. */
    fun rms(samples: ShortArray, count: Int): Int {
        if (count <= 0) return 0
        var sum = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count).toInt()
    }

    fun segmentLog(sessionId: Long, source: Source, peak: Int, rms: Int, decision: Decision): String {
        val wire = if (source == Source.Microphone) "mic" else "internal"
        return "session=$sessionId source=$wire peak=$peak rms=$rms decision=${decision.name} reason=${decision.reason}"
    }
}
