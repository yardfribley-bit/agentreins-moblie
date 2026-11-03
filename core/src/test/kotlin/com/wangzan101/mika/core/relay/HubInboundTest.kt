package com.wangzan101.mika.core.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class HubInboundTest {
    @Test fun keepsOwnerInstructionTextAndDelegatedOrders() {
        val owner = HubRelayEvent(2, "message", fromOwner = true, messageKind = "instruction", text = "查一下 内存")
        val delegated = HubRelayEvent(
            3, "message", fromOwner = false, messageKind = "instruction", text = "代发的", delegated = true,
        )
        val mentor = HubRelayEvent(4, "message", fromOwner = false, messageKind = "instruction", text = "别人")
        val progress = HubRelayEvent(5, "message", fromOwner = true, messageKind = "progress", text = "进度")
        assertTrue(HubInstructionFilter.accept(owner))
        assertTrue(HubInstructionFilter.accept(delegated))
        assertFalse(HubInstructionFilter.accept(mentor))
        assertFalse(HubInstructionFilter.accept(progress))
        assertEquals("查一下 内存", owner.text)
    }

    @Test fun planAcksTheInstructionAndAdvancesCursorWithoutReplay() {
        val page = HubRelayPage(
            listOf(
                HubRelayEvent(1, "joined", fromOwner = true),
                HubRelayEvent(2, "message", fromOwner = true, messageKind = "instruction", text = "查一下 内存"),
            ),
            nextCursor = 2,
            hasMore = false,
        )
        val plan = HubInbound.plan(page, handled = 0)
        assertEquals(2L, plan.ackSeq)
        assertEquals(listOf(HubInstruction(2, "查一下 内存")), plan.fresh)
        assertEquals(2L, plan.cursor)
        val again = HubInbound.plan(page, handled = plan.ackSeq!!)
        assertNull(again.ackSeq)
        assertTrue(again.fresh.isEmpty())
        val queued = HubInbound.enqueue(HubInbound.enqueue(emptyList(), plan.fresh), plan.fresh)
        assertEquals(1, queued.size)
        assertEquals(queued, HubInbound.decodeQueue(HubInbound.encodeQueue(queued)))
    }

    @Test fun queuesWhileARunIsActiveThenStartsTheNext() {
        val gate = HubRunGate(stallMs = 20_000)
        val queued = listOf(HubInstruction(2, "查一下 内存"), HubInstruction(5, "再看一眼"))
        val held = gate.poll(now = 0, busy = true, queue = queued)
        assertNull(held.second)
        assertEquals(queued, held.first)
        val started = gate.poll(now = 1_000, busy = false, queue = held.first)
        assertEquals(HubInstruction(2, "查一下 内存"), started.second)
        assertEquals(listOf(HubInstruction(5, "再看一眼")), started.first)
        val still = gate.poll(now = 2_000, busy = true, queue = started.first)
        assertNull(still.second)
        val done = gate.poll(now = 3_000, busy = false, queue = still.first)
        assertEquals(HubInstruction(5, "再看一眼"), done.second)
        assertTrue(done.first.isEmpty())
    }

    @Test fun downloadWaitDoesNotLookLikeAStall() {
        val gate = HubRunGate(stallMs = 20_000)
        val queued = listOf(HubInstruction(2, "带附件"))
        val started = gate.poll(now = 0, busy = false, queue = queued)
        assertEquals(HubInstruction(2, "带附件"), started.second)
        gate.noteStarting(25_000)
        val still = gate.poll(now = 30_000, busy = false, queue = emptyList())
        assertNull(still.second)
        assertEquals(HubInstruction(2, "带附件"), gate.inflight)
        val stalled = gate.poll(now = 50_000, busy = false, queue = emptyList())
        assertEquals(listOf(HubInstruction(2, "带附件")), stalled.first)
        assertNull(gate.inflight)
    }

    @Test fun keepsAttachmentMetadataAndDropsTheSignedUrl() {
        val raw = """
            {"events":[{"seq":250,"kind":"message","from_owner":true,"sender":{"role":"owner","name":"o"},
            "message":{"kind":"instruction","text":"post this","attachments":[
            {"id":"fl_52161fe383bbac29a14b66a9","name":"agentreins-ime.png","size":114848,"sha256":"abc","type":"image/png","url":"https://files.example/a?sig=deadbeef","download_url":"https://files.example/a?sig=deadbeef"}
            ]}}],"next_cursor":250,"has_more":false}
        """.trim()
        val page = HubInstructionFilter.parse(Json.parseToJsonElement(raw).jsonObject)
        val event = page.events.single()
        assertEquals("fl_52161fe383bbac29a14b66a9", event.attachments.single().fileId)
        assertEquals("agentreins-ime.png", event.attachments.single().name)
        assertEquals(114848L, event.attachments.single().size)
        assertEquals("image/png", event.attachments.single().mime)
        val plan = HubInbound.plan(page, handled = 0)
        assertEquals(250L, plan.ackSeq)
        val stored = HubInbound.encodeQueue(plan.fresh)
        assertFalse(stored.contains("sig="))
        assertFalse(stored.contains("http"))
        assertFalse(stored.contains("deadbeef"))
        assertEquals(plan.fresh, HubInbound.decodeQueue(stored))
        val withId = """
            {"events":[{"seq":4550,"kind":"message","from_owner":true,"request_id":"x-ime-github-20261103-1",
            "message":{"kind":"instruction","text":"open the image"}}],"next_cursor":4550,"has_more":false}
        """.trim()
        val asked = HubInbound.plan(HubInstructionFilter.parse(Json.parseToJsonElement(withId).jsonObject), 0).fresh.single()
        assertEquals("x-ime-github-20261103-1", asked.requestId)
        assertEquals(asked, HubInbound.decodeQueue(HubInbound.encodeQueue(listOf(asked))).single())
        val old = HubInbound.decodeQueue("2\u001e查一下 内存")
        assertEquals(listOf(HubInstruction(2, "查一下 内存")), old)
    }

    @Test fun downloadFailureStillDescribesTheTask() {
        val instruction = HubInstruction(250, "post this", listOf(HubAttachmentRef("fl_52161fe383bbac29a14b66a9", "agentreins-ime.png")))
        var called = false
        val prepared = HubOwnerLaunch.decorate(instruction) {
            called = true
            throw IOException("https://files.example/a?sig=deadbeef bearer da_secret")
        }
        assertTrue(called)
        assertTrue(prepared.goal.startsWith("post this"))
        assertTrue(prepared.goal.contains("fl_52161fe383bbac29a14b66a9"))
        assertTrue(prepared.resultNote!!.contains("fl_52161fe383bbac29a14b66a9"))
        assertFalse(prepared.goal.contains("sig="))
        assertFalse(prepared.resultNote!!.contains("da_secret"))
        val plain = HubOwnerLaunch.decorate(HubInstruction(1, "only text")) { error("should not prepare") }
        assertEquals("only text", plain.goal)
        assertNull(plain.resultNote)
    }
}
