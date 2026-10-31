package com.wangzan101.mika.audio

import org.junit.Assert.*
import org.junit.Test

class LiveAudioFallbackTest {
    @Test fun threeSilentInternalSegmentsSwitchOnce() {
        var state = LiveAudioFallback.State(LiveAudioFallback.Source.Internal)
        repeat(2) {
            val kept = LiveAudioFallback.onSegment(state, 0)
            assertTrue(kept is LiveAudioFallback.Decision.Keep)
            state = kept.state
        }
        val switched = LiveAudioFallback.onSegment(state, LiveAudioFallback.SILENCE_PEAK - 1)
        assertTrue(switched is LiveAudioFallback.Decision.UseMicrophone)
        assertEquals(LiveAudioFallback.FALLBACK_STATUS, (switched as LiveAudioFallback.Decision.UseMicrophone).status)
        assertEquals(LiveAudioFallback.Source.Microphone, switched.state.source)
        assertEquals(0, switched.state.silentStreak)
        assertTrue(switched.state.fellBack)
        val again = LiveAudioFallback.onSegment(switched.state.copy(silentStreak = 2), 0)
        assertTrue(again is LiveAudioFallback.Decision.ReportSilence)
        assertEquals(LiveAudioFallback.MIC_SILENCE, (again as LiveAudioFallback.Decision.ReportSilence).message)
        assertEquals(LiveAudioFallback.Source.Microphone, again.state.source)
        assertTrue(again.state.fellBack)
    }

    @Test fun explicitMicrophoneStartDoesNotFlip() {
        val silence = LiveAudioFallback.onSegment(
            LiveAudioFallback.State(LiveAudioFallback.Source.Microphone, silentStreak = 2),
            0,
        )
        assertTrue(silence is LiveAudioFallback.Decision.ReportSilence)
        assertEquals(LiveAudioFallback.MIC_SILENCE, (silence as LiveAudioFallback.Decision.ReportSilence).message)
        assertFalse(silence.state.fellBack)
        assertEquals(LiveAudioFallback.Source.Microphone, silence.state.source)
    }

    @Test fun audiblePeakResetsTheStreakAndStaysInternal() {
        val state = LiveAudioFallback.State(LiveAudioFallback.Source.Internal, silentStreak = 2)
        val heard = LiveAudioFallback.onSegment(state, LiveAudioFallback.SILENCE_PEAK)
        assertTrue(heard is LiveAudioFallback.Decision.Hear)
        assertEquals(0, heard.state.silentStreak)
        assertEquals(LiveAudioFallback.Source.Internal, heard.state.source)
        assertFalse(heard.state.fellBack)
        var again = LiveAudioFallback.State(LiveAudioFallback.Source.Internal)
        repeat(2) { again = LiveAudioFallback.onSegment(again, 0).state }
        again = LiveAudioFallback.onSegment(again, 500).state
        assertEquals(0, again.silentStreak)
        val still = LiveAudioFallback.onSegment(again, 0)
        assertTrue(still is LiveAudioFallback.Decision.Keep)
    }

    @Test fun failedFallbackDoesNotSwitchAgain() {
        val state = LiveAudioFallback.State(LiveAudioFallback.Source.Internal, silentStreak = 2, fellBack = true)
        val silence = LiveAudioFallback.onSegment(state, 0)
        assertTrue(silence is LiveAudioFallback.Decision.ReportSilence)
        assertEquals(LiveAudioFallback.INTERNAL_SILENCE, (silence as LiveAudioFallback.Decision.ReportSilence).message)
        assertEquals(LiveAudioFallback.Source.Internal, silence.state.source)
        assertTrue(silence.state.fellBack)
    }
}
