package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.phone.LiveAudioPort
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LiveAudioToolTest {
    @Test fun audioToolRequiresPortAndTracksCursorPerTask() = runTest {
        assertFalse(toolDispatcher(FakeScreen()).offeredNow().any { it.name == "phone_listen_live" })
        val seen = mutableListOf<Long>()
        var stopped = false
        val port = object : LiveAudioPort {
            override suspend fun read(after: Long, waitSeconds: Int): JsonObject {
                seen += after
                return buildJsonObject { put("ok", true); put("cursor", 12L); put("status", "listening") }
            }
            override fun stop(): Boolean { stopped = true; return true }
        }
        val task = toolDispatcher(FakeScreen(), liveAudio = port)
        assertTrue(task.offeredNow().any { it.name == "phone_listen_live" })
        task.execute(Part.ToolCall("1", "phone_listen_live", "{}"))
        task.execute(Part.ToolCall("2", "phone_listen_live", "{}"))
        assertEquals(listOf(0L, 12L), seen)
        assertFalse(task.writesScreen("phone_listen_live"))
        task.execute(Part.ToolCall("3", "phone_listen_live", "{\"stop\":true}"))
        assertTrue(stopped)
        val other = toolDispatcher(FakeScreen(), liveAudio = port)
        other.execute(Part.ToolCall("4", "phone_listen_live", "{}"))
        assertEquals(0L, seen.last())
        assertFalse(task.offeredNow().any { it.name == "phone_configure_live_audio" })
    }

    @Test fun configureSavesPresetAndNeverReturnsTheKey() = runTest {
        assertFalse(toolDispatcher(FakeScreen()).offeredNow().any { it.name == "phone_configure_live_audio" })
        val secret = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz1234"
        val store = MemorySetup()
        val task = toolDispatcher(FakeScreen(), liveAudioSetup = store)
        assertTrue(task.offeredNow().any { it.name == "phone_configure_live_audio" })
        assertFalse(task.writesScreen("phone_configure_live_audio"))
        val saved = task.execute(Part.ToolCall("1", "phone_configure_live_audio", """{"key":"$secret"}"""))
        val shown = saved.content.toString()
        assertEquals(true, saved.content["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("openrouter", saved.content["preset"]?.jsonPrimitive?.content)
        assertEquals("https://openrouter.ai/api/v1/audio/transcriptions", saved.content["endpoint"]?.jsonPrimitive?.content)
        assertEquals("openai/whisper-large-v3", saved.content["model"]?.jsonPrimitive?.content)
        assertEquals(true, saved.content["keySet"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(false, saved.content["listeningStarted"]?.jsonPrimitive?.booleanOrNull)
        assertFalse(shown.contains(secret))
        assertFalse(shown.contains("abcdefghijklmnopqrstuvwxyz1234"))
        assertEquals(secret, store.stored.key)
        assertTrue(saved.content["keyHint"]?.jsonPrimitive?.content?.startsWith("sk-or-") == true)
        assertFalse(saved.content["keyHint"]?.jsonPrimitive?.content?.contains("abcdefghijklmnopqrstuvwxyz1234") == true)

        val mismatch = task.execute(Part.ToolCall("2", "phone_configure_live_audio",
            """{"preset":"openai","key":"$secret"}"""))
        assertEquals("openai", mismatch.content["preset"]?.jsonPrimitive?.content)
        assertTrue(mismatch.content["warning"]?.jsonPrimitive?.content?.contains("HTTP 401") == true)
        assertFalse(mismatch.content.toString().contains(secret))
    }
}

private class MemorySetup : com.wangzan101.mika.core.cap.phone.LiveAudioSetup {
    var stored = com.wangzan101.mika.core.cap.phone.AsrStored(
        "https://api.openai.com/v1/audio/transcriptions", "whisper-1", "", false,
    )
    override fun view() = com.wangzan101.mika.core.cap.phone.AsrConfig.publish(stored)
    override fun apply(request: com.wangzan101.mika.core.cap.phone.AsrSetupRequest): com.wangzan101.mika.core.cap.phone.AsrApplyResult {
        return when (val resolved = com.wangzan101.mika.core.cap.phone.AsrConfig.resolve(stored, request)) {
            is com.wangzan101.mika.core.cap.phone.AsrResolve.Failed ->
                com.wangzan101.mika.core.cap.phone.AsrApplyResult(false, resolved.code, resolved.message, view())
            is com.wangzan101.mika.core.cap.phone.AsrResolve.Ready -> {
                stored = resolved.stored
                com.wangzan101.mika.core.cap.phone.AsrApplyResult(true, null, null, view())
            }
        }
    }
}
