package com.wangzan101.mika.core.cap.phone.speech

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechToolTest {
    @Test fun speechToolsStayHiddenWithoutPorts() = runTest {
        val names = toolDispatcher(FakeScreen()).offeredNow().map { it.name }.toSet()
        assertFalse("phone_speak" in names)
        assertFalse("phone_take_mic" in names)
        assertFalse("phone_leave_mic" in names)
        assertFalse("phone_configure_speech" in names)
    }

    @Test fun speakStopDoesNotReleaseTheSeatAndTakeMicIsTwoSteps() = runTest {
        val speech = FakeSpeech()
        val seat = FakeSeat()
        val task = toolDispatcher(FakeScreen(), speech = speech, micSeat = seat)
        val stopped = task.execute(Part.ToolCall("1", "phone_speak", """{"stop":true}"""))
        assertEquals(false, stopped.content["releaseMic"]?.jsonPrimitive?.boolean)
        assertTrue(speech.stopped)
        assertFalse(speech.releaseMic)
        task.execute(Part.ToolCall("2", "phone_take_mic", "{}"))
        assertEquals(1, seat.requests)
        task.execute(Part.ToolCall("3", "phone_take_mic", """{"seated":true}"""))
        assertEquals(1, seat.held)
        task.execute(Part.ToolCall("4", "phone_leave_mic", "{}"))
        assertEquals(1, seat.released)
        assertFalse(task.writesScreen("phone_speak"))
        assertFalse(task.writesScreen("phone_take_mic"))
    }

    @Test fun configureNeverReturnsTheKeyOrTurnsSoundOn() = runTest {
        val secret = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz1234"
        val store = MemorySpeech()
        val task = toolDispatcher(FakeScreen(), speechSetup = store)
        val saved = task.execute(Part.ToolCall("1", "phone_configure_speech", """{"key":"$secret","voice":"coral"}"""))
        val shown = saved.content.toString()
        assertEquals(true, saved.content["ok"]?.jsonPrimitive?.boolean)
        assertFalse(shown.contains(secret))
        assertFalse(shown.contains("abcdefghijklmnopqrstuvwxyz1234"))
        assertEquals(false, store.stored.allow)
        assertEquals(false, saved.content["allowChanged"]?.jsonPrimitive?.boolean)
        assertEquals(secret, store.stored.key)
        assertEquals("openrouter", saved.content["preset"]?.jsonPrimitive?.content)
    }
}

private class FakeSpeech : SpeechPort {
    var stopped = false
    var releaseMic = false
    override suspend fun speak(text: String) = buildJsonObject { put("ok", true); put("status", "finished"); put("continueListening", true) }
    override fun stop(releaseMic: Boolean): JsonObject {
        stopped = true
        this.releaseMic = releaseMic
        return buildJsonObject { put("ok", true); put("status", "stopped"); put("releaseMic", releaseMic) }
    }
    override fun status() = buildJsonObject { put("ok", true) }
}

private class FakeSeat : MicSeatPort {
    var requests = 0
    var held = 0
    var released = 0
    override suspend fun request() = buildJsonObject { requests++; put("ok", false); put("error", "confirmation_required") }
    override fun markHeld() = buildJsonObject { held++; put("ok", true); put("seat", "held") }
    override fun release() = buildJsonObject { released++; put("ok", true) }
    override fun view() = buildJsonObject { put("seat", "off") }
}

private class MemorySpeech : SpeechSetup {
    var stored = SpeechStored()
    override fun view() = SpeechConfig.publish(stored)
    override fun apply(request: SpeechSetupRequest): SpeechApplyResult =
        when (val resolved = SpeechConfig.resolve(stored, request)) {
            is SpeechResolve.Failed -> SpeechApplyResult(false, resolved.code, resolved.message, view())
            is SpeechResolve.Ready -> {
                stored = resolved.stored
                SpeechApplyResult(true, null, null, view())
            }
        }
}
