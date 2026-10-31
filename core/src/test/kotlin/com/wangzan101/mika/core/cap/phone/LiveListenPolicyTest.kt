package com.wangzan101.mika.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveListenPolicyTest {
    @Test fun threeQuietInternalSegmentsFallBackOnce() {
        var state = LiveListenPolicy.State(LiveListenPolicy.Source.Internal)
        repeat(2) {
            val kept = LiveListenPolicy.onSegment(state, 0)
            assertTrue(kept is LiveListenPolicy.Decision.Keep)
            state = kept.state
        }
        val switched = LiveListenPolicy.onSegment(state, LiveListenPolicy.SILENCE_PEAK - 1)
        assertTrue(switched is LiveListenPolicy.Decision.UseMicrophone)
        assertEquals(LiveListenPolicy.Source.Microphone, switched.state.source)
        assertTrue(switched.state.fellBack)
        val again = LiveListenPolicy.onSegment(switched.state.copy(silentStreak = 2), 0)
        assertTrue(again is LiveListenPolicy.Decision.ReportSilence)
        assertEquals(LiveListenPolicy.Source.Microphone, again.state.source)
    }

    @Test fun nextSessionRetriesInternalWhenTheUserDidNotChooseTheMic() {
        var state = LiveListenPolicy.State(LiveListenPolicy.Source.Internal)
        repeat(3) { state = LiveListenPolicy.onSegment(state, 0).state }
        assertEquals(LiveListenPolicy.Source.Microphone, state.source)
        assertFalse(LiveListenPolicy.microphoneForNextSession(savedUserPref = false))
        val retry = LiveListenPolicy.State(LiveListenPolicy.Source.Internal)
        val heard = LiveListenPolicy.onSegment(retry, LiveListenPolicy.SILENCE_PEAK)
        assertEquals(LiveListenPolicy.Source.Internal, heard.state.source)
        assertFalse(heard.state.fellBack)
    }

    @Test fun savedMicPrefStaysOnForTheNextSession() {
        assertTrue(LiveListenPolicy.microphoneForNextSession(savedUserPref = true))
    }

    @Test fun eachListenGetsANewSessionAndSegmentIdsRestart() {
        val cursor = LiveListenCursor()
        val first = cursor.begin()
        repeat(92) { cursor.allocateSegment() }
        assertEquals(92, cursor.nextSegment)
        cursor.end()
        assertEquals(0, cursor.nextSegment)
        val second = cursor.begin()
        assertEquals(first + 1, second)
        assertEquals(1, cursor.allocateSegment())
    }

    @Test fun segmentLogNamesSourcePeakRmsDecisionAndSession() {
        val decision = LiveListenPolicy.onSegment(
            LiveListenPolicy.State(LiveListenPolicy.Source.Internal, silentStreak = 2),
            0,
        )
        val line = LiveListenPolicy.segmentLog(7, LiveListenPolicy.Source.Internal, peak = 40, rms = 11, decision)
        assertEquals(LiveListenPolicy.LOG_TAG, "LiveAudio")
        assertEquals(
            "session=7 source=internal peak=40 rms=11 decision=fallback reason=internal silent for 3 segments",
            line,
        )
        val samples = ShortArray(4) { 3 }
        assertEquals(3, LiveListenPolicy.rms(samples, samples.size))
    }
}
