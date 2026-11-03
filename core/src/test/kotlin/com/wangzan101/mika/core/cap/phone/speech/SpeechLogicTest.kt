package com.wangzan101.mika.core.cap.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {
    @Test fun splitsChineseSentencesAndKeepsTheFirstShort() {
        val parts = SpeechChunker.chunks("你好。我是助手！今天过得怎么样？")
        assertEquals(listOf("你好。", "我是助手！", "今天过得怎么样？"), parts)
        assertTrue(parts.first().length < 20)
    }

    @Test fun doesNotSplitDecimalsOrEllipsis() {
        assertEquals(listOf("价格是3.14元。"), SpeechChunker.chunks("价格是3.14元。"))
        assertEquals(listOf("Hi..."), SpeechChunker.chunks("Hi..."))
    }

    @Test fun splitsALongSentenceOnCommas() {
        val text = "甲".repeat(30) + "，" + "乙".repeat(30) + "，" + "丙".repeat(30) + "。"
        val parts = SpeechChunker.chunks(text)
        assertTrue(parts.size >= 2)
        assertTrue(parts.all { it.length <= SpeechChunker.MAX_CHARS })
        assertTrue(parts.first().startsWith("甲"))
    }

    @Test fun blankIsEmptyAndNewlinesSplit() {
        assertEquals(emptyList<String>(), SpeechChunker.chunks("  \n  "))
        assertEquals(listOf("第一句", "第二句"), SpeechChunker.chunks("第一句\n第二句"))
    }
}

class FloorMachineTest {
    @Test fun speakThenFinishReturnsToIdle() {
        val session = FloorSession()
        val start = session.on(FloorEvent.Start)
        assertEquals(Floor.Speaking, start.floor)
        assertFalse(start.stopAudio)
        assertEquals(Floor.Idle, session.on(FloorEvent.Finished).floor)
    }

    @Test fun ownerStopEndsAudioWithoutReleasingTheSeat() {
        val session = FloorSession()
        session.on(FloorEvent.Start)
        val step = session.on(FloorEvent.OwnerStop)
        assertEquals(Floor.Stopped, step.floor)
        assertTrue(step.stopAudio)
        assertFalse(step.releaseMic)
    }

    @Test fun tapStopEndsAudioAndReleasesTheSeatEvenWhenIdle() {
        val idle = FloorSession().on(FloorEvent.TapStop)
        assertEquals(Floor.Idle, idle.floor)
        assertFalse(idle.stopAudio)
        assertTrue(idle.releaseMic)
        val session = FloorSession()
        session.on(FloorEvent.Start)
        val step = session.on(FloorEvent.TapStop)
        assertEquals(Floor.Stopped, step.floor)
        assertTrue(step.stopAudio)
        assertTrue(step.releaseMic)
    }

    @Test fun othersTalkingYieldsOnlyWhileSpeaking() {
        val idle = FloorSession()
        val ignored = idle.on(FloorEvent.OthersTalking)
        assertEquals(Floor.Idle, ignored.floor)
        assertFalse(ignored.stopAudio)
        idle.on(FloorEvent.Start)
        val step = idle.on(FloorEvent.OthersTalking)
        assertEquals(Floor.Yielded, step.floor)
        assertTrue(step.stopAudio)
        assertFalse(step.releaseMic)
        assertEquals(Floor.Idle, idle.on(FloorEvent.Finished).floor)
        assertEquals(Floor.Speaking, idle.on(FloorEvent.Start).floor)
    }

    @Test fun stoppedCanSpeakAgain() {
        val session = FloorSession()
        session.on(FloorEvent.Start)
        session.on(FloorEvent.OwnerStop)
        assertEquals(Floor.Speaking, session.on(FloorEvent.Start).floor)
    }
}

class EchoGateTest {
    @Test fun ownScriptIsEchoAndAShortStopIsAnInterrupt() {
        val script = "今天天气不错，我们聊聊直播。"
        assertEquals(Heard.Echo, EchoGate.interpret(script, script, inWindow = true, CapturePath.Microphone))
        assertEquals(Heard.Interrupt, EchoGate.interpret("停止", script, inWindow = true, CapturePath.Microphone))
        assertEquals(Heard.Interrupt, EchoGate.interpret("别说了。", script, inWindow = true, CapturePath.Microphone))
    }

    @Test fun stopInsideTheScriptIsNotAnInterrupt() {
        val script = "请你现在停止说话"
        assertEquals(Heard.Echo, EchoGate.interpret("停止", script, inWindow = true, CapturePath.Microphone))
    }

    @Test fun outsideTheWindowIsSomeoneElse() {
        assertEquals(Heard.Other, EchoGate.interpret("停止", "你好", inWindow = false, CapturePath.Microphone))
        assertEquals(Heard.Silence, EchoGate.interpret("   ", "你好", inWindow = true, CapturePath.Microphone))
    }

    @Test fun playbackSpeechThatIsNotOursYieldsAsOther() {
        assertEquals(Heard.Other, EchoGate.interpret("你刚才说的不对", "今天天气不错", inWindow = true, CapturePath.Playback))
        assertEquals(Heard.Echo, EchoGate.interpret("你刚才说的不对", "今天天气不错", inWindow = true, CapturePath.Microphone))
    }

    @Test fun windowCoversSpeechAndAShortTail() {
        assertFalse(EchoGate.inWindow(1_000, speakStart = 2_000, speakEnd = 0))
        assertTrue(EchoGate.inWindow(2_500, speakStart = 2_000, speakEnd = 0))
        assertTrue(EchoGate.inWindow(3_500, speakStart = 2_000, speakEnd = 3_000))
        assertFalse(EchoGate.inWindow(3_000 + EchoGate.TAIL_MS + 1, speakStart = 2_000, speakEnd = 3_000))
    }
}

class VadYieldTest {
    @Test fun onlyPlaybackEnergyWhileSpeakingYields() {
        assertTrue(VadYield.shouldYield(CapturePath.Playback, speaking = true, peak = VadYield.SPEECH_PEAK))
        assertFalse(VadYield.shouldYield(CapturePath.Microphone, speaking = true, peak = 20_000))
        assertFalse(VadYield.shouldYield(CapturePath.Playback, speaking = false, peak = 20_000))
        assertFalse(VadYield.shouldYield(CapturePath.Playback, speaking = true, peak = VadYield.SPEECH_PEAK - 1))
    }
}

class OnMicHearingTest {
    @Test fun offMicDoesNotClaimSilence() {
        val advice = OnMicHearing.advise(false, captureIsMic = true, silentStreak = 9, lastPeak = 0, clientSilenced = true)
        assertEquals("not_on_mic", advice.hearing)
        assertEquals(null, advice.fallback)
    }

    @Test fun silencedMicFallsBackToOnScreenChat() {
        val advice = OnMicHearing.advise(true, captureIsMic = true, silentStreak = 3, lastPeak = 10, clientSilenced = false)
        assertEquals("silent", advice.hearing)
        assertFalse(advice.playbackCaptureUsable)
        assertTrue(advice.fallback!!.contains("phone_observe"))
        assertTrue(advice.summary.contains("voice-communication"))
    }

    @Test fun platformSilenceAndAudibleEnergy() {
        val forced = OnMicHearing.advise(true, captureIsMic = true, silentStreak = 0, lastPeak = 4_000, clientSilenced = true)
        assertEquals("silent", forced.hearing)
        val heard = OnMicHearing.advise(true, captureIsMic = true, silentStreak = 0, lastPeak = 4_000, clientSilenced = false)
        assertEquals("audible", heard.hearing)
        assertEquals(null, heard.fallback)
    }

    @Test fun internalSilenceWhileOnMicDoesNotTrustPlaybackCapture() {
        val advice = OnMicHearing.advise(true, captureIsMic = false, silentStreak = 3, lastPeak = 0, clientSilenced = false)
        assertEquals("silent", advice.hearing)
        assertFalse(advice.playbackCaptureUsable)
    }
}

class MicSeatTest {
    @Test fun seatRequiresAllowThenOwnerThenScreen() {
        val seat = MicSeatMachine()
        val blocked = seat.request(allowSpeech = false)
        assertEquals("speech_disabled", blocked.code)
        assertEquals(Seat.Off, seat.seat)
        val waiting = seat.request(allowSpeech = true)
        assertEquals("confirmation_required", waiting.code)
        assertEquals(Seat.Awaiting, waiting.seat)
        assertFalse(seat.markHeld().ok)
        val declined = MicSeatMachine().also { it.request(true) }.ownerAnswer(false)
        assertEquals("confirmation_declined", declined.code)
        val machine = MicSeatMachine()
        machine.request(true)
        val allowed = machine.ownerAnswer(true)
        assertEquals(Seat.Authorized, allowed.seat)
        assertTrue(allowed.message.contains("连麦"))
        assertEquals(Seat.Held, machine.markHeld().seat)
        val released = machine.release()
        assertEquals(Seat.Off, released.seat)
        assertTrue(released.releaseUi)
    }

    @Test fun tapReleaseFromOffDoesNotAskForUi() {
        val released = MicSeatMachine().release()
        assertFalse(released.releaseUi)
        assertTrue(released.ok)
    }
}

class SpeechConfigTest {
    @Test fun defaultsAreOffAndDoNotPrintTheKey() {
        val stored = SpeechStored(key = "sk-or-v1-abcdefghijklmnopqrstuvwxyz1234")
        assertFalse(stored.allow)
        assertEquals("openrouter", SpeechConfig.publish(stored).preset)
        assertFalse(stored.toString().contains("abcdefghijklmnopqrstuvwxyz1234"))
        assertTrue(SpeechConfig.publish(stored).keyHint.startsWith("sk-or-"))
        assertFalse(SpeechConfig.publish(stored).keyHint.contains("abcdefghijklmnopqrstuvwxyz1234"))
    }

    @Test fun keyAloneSelectsOpenRouterWhenCurrentValuesAreStillAPreset() {
        val secret = "sk-or-v1-abcdefghijklmnopqrstuvwxyz1234"
        val resolved = SpeechConfig.resolve(SpeechStored(), SpeechSetupRequest(key = secret)) as SpeechResolve.Ready
        assertEquals(SpeechPresets.openRouter.endpoint, resolved.stored.endpoint)
        assertEquals(SpeechPresets.openRouter.model, resolved.stored.model)
        assertEquals("coral", resolved.stored.voice)
        assertFalse(resolved.stored.allow)
    }

    @Test fun mismatchIsSavedAndTheAllowSwitchStays() {
        val secret = "sk-or-v1-abcdefghijklmnopqrstuvwxyz1234"
        val current = SpeechStored(allow = true)
        val resolved = SpeechConfig.resolve(current, SpeechSetupRequest(preset = "openai", key = secret)) as SpeechResolve.Ready
        assertTrue(resolved.stored.allow)
        assertEquals("gpt-4o-mini-tts", resolved.stored.model)
        val view = SpeechConfig.publish(resolved.stored)
        assertTrue(view.warning!!.contains("HTTP 401"))
        assertFalse(view.toString().contains(secret))
    }

    @Test fun systemPresetNeedsNoEndpointAndFastVoiceIsChecked() {
        val system = SpeechConfig.resolve(SpeechStored(), SpeechSetupRequest(preset = "system")) as SpeechResolve.Ready
        assertEquals("system", system.stored.provider)
        assertEquals("", system.stored.endpoint)
        val bad = SpeechConfig.resolve(SpeechStored(), SpeechSetupRequest(preset = "openai-fast", voice = "coral"))
        assertTrue(bad is SpeechResolve.Failed && (bad as SpeechResolve.Failed).code == "invalid_voice")
        val fast = SpeechConfig.resolve(SpeechStored(), SpeechSetupRequest(preset = "openai-fast")) as SpeechResolve.Ready
        assertEquals("tts-1", fast.stored.model)
        assertEquals("nova", fast.stored.voice)
    }

    @Test fun rateVolumeAndExternalRoute() {
        val bad = SpeechConfig.resolve(SpeechStored(), SpeechSetupRequest(rate = 3f))
        assertTrue(bad is SpeechResolve.Failed)
        val external = SpeechConfig.resolve(SpeechStored(), SpeechSetupRequest(route = "external")) as SpeechResolve.Ready
        assertEquals(SpeechRoute.EXTERNAL, external.stored.route)
        assertFalse(SpeechConfig.publish(external.stored).routeAvailable)
        assertTrue(SpeechRoute.profile(SpeechRoute.SPEAKER).available)
        assertEquals("assistance_accessibility", SpeechRoute.profile(SpeechRoute.SPEAKER).usage)
        assertTrue(SpeechRoute.profile(SpeechRoute.SPEAKER).forceBuiltInSpeaker)
    }

    @Test fun requestDoesNotEchoTheKey() {
        val secret = "custom-token-value-xyz"
        val request = SpeechSetupRequest(key = secret, paste = "key=$secret")
        assertFalse(request.toString().contains(secret))
    }
}
