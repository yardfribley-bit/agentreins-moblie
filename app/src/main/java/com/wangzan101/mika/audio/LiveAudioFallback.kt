package com.wangzan101.mika.audio

/**
 * Peak silence for one capture segment. Three consecutive quiet segments (about 24s)
 * in internal mode switch to the microphone once. Microphone silence never flips back,
 * including after that automatic switch. An explicit internal start is honored until
 * this silence condition fires again.
 */
internal object LiveAudioFallback {
    const val SILENCE_PEAK = 120
    const val SILENT_SEGMENTS = 3
    const val FALLBACK_STATUS = "内部无声，已改用麦克风外放收音"
    const val MIC_SILENCE = "未检测到声音，请检查外放音量和麦克风"
    const val INTERNAL_SILENCE = "未采集到抖音声音：请关闭 App 静音；若仍无声，停止后改用麦克风外放收音"

    enum class Source { Internal, Microphone }

    data class State(
        val source: Source,
        val silentStreak: Int = 0,
        /** True after an automatic internal→mic attempt. Blocks a second flip. */
        val fellBack: Boolean = false,
    )

    sealed class Decision {
        abstract val state: State
        data class Keep(override val state: State) : Decision()
        data class Hear(override val state: State) : Decision()
        data class UseMicrophone(override val state: State, val status: String) : Decision()
        data class ReportSilence(override val state: State, val message: String) : Decision()
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
}
