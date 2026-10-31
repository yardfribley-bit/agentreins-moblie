package com.wangzan101.mika.audio

import com.wangzan101.mika.core.cap.phone.LiveListenPolicy
import org.junit.Assert.*
import org.junit.Test

class LiveAudioFallbackTest {
    @Test fun threeSilentInternalSegmentsSwitchOnce() {
        var state = LiveListenPolicy.State(LiveListenPolicy.Source.Internal)
        repeat(2) {
            val kept = LiveAudioFallback.onSegment(state, 0)
            assertTrue(kept is LiveListenPolicy.Decision.Keep)
            state = kept.state
        }
        val switched = LiveAudioFallback.onSegment(state, LiveAudioFallback.SILENCE_PEAK - 1)
        assertTrue(switched is LiveListenPolicy.Decision.UseMicrophone)
        assertEquals(LiveAudioFallback.FALLBACK_STATUS, (switched as LiveListenPolicy.Decision.UseMicrophone).status)
        assertEquals(LiveListenPolicy.Source.Microphone, switched.state.source)
        assertEquals(0, switched.state.silentStreak)
        assertTrue(switched.state.fellBack)
        val again = LiveAudioFallback.onSegment(switched.state.copy(silentStreak = 2), 0)
        assertTrue(again is LiveListenPolicy.Decision.ReportSilence)
        assertEquals(LiveAudioFallback.MIC_SILENCE, (again as LiveListenPolicy.Decision.ReportSilence).message)
        assertEquals(LiveListenPolicy.Source.Microphone, again.state.source)
        assertTrue(again.state.fellBack)
    }

    @Test fun explicitMicrophoneStartDoesNotFlip() {
        val silence = LiveAudioFallback.onSegment(
            LiveListenPolicy.State(LiveListenPolicy.Source.Microphone, silentStreak = 2),
            0,
        )
        assertTrue(silence is LiveListenPolicy.Decision.ReportSilence)
        assertEquals(LiveAudioFallback.MIC_SILENCE, (silence as LiveListenPolicy.Decision.ReportSilence).message)
        assertFalse(silence.state.fellBack)
        assertEquals(LiveListenPolicy.Source.Microphone, silence.state.source)
    }

    @Test fun audiblePeakResetsTheStreakAndStaysInternal() {
        val state = LiveListenPolicy.State(LiveListenPolicy.Source.Internal, silentStreak = 2)
        val heard = LiveAudioFallback.onSegment(state, LiveAudioFallback.SILENCE_PEAK)
        assertTrue(heard is LiveListenPolicy.Decision.Hear)
        assertEquals(0, heard.state.silentStreak)
        assertEquals(LiveListenPolicy.Source.Internal, heard.state.source)
        assertFalse(heard.state.fellBack)
        var again = LiveListenPolicy.State(LiveListenPolicy.Source.Internal)
        repeat(2) { again = LiveAudioFallback.onSegment(again, 0).state }
        again = LiveAudioFallback.onSegment(again, 500).state
        assertEquals(0, again.silentStreak)
        val still = LiveAudioFallback.onSegment(again, 0)
        assertTrue(still is LiveListenPolicy.Decision.Keep)
    }

    @Test fun failedFallbackDoesNotSwitchAgain() {
        val state = LiveListenPolicy.State(LiveListenPolicy.Source.Internal, silentStreak = 2, fellBack = true)
        val silence = LiveAudioFallback.onSegment(state, 0)
        assertTrue(silence is LiveListenPolicy.Decision.ReportSilence)
        assertEquals(LiveAudioFallback.INTERNAL_SILENCE, (silence as LiveListenPolicy.Decision.ReportSilence).message)
        assertEquals(LiveListenPolicy.Source.Internal, silence.state.source)
        assertTrue(silence.state.fellBack)
    }
}
