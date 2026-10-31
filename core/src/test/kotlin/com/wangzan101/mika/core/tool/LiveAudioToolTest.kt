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
    }
}
