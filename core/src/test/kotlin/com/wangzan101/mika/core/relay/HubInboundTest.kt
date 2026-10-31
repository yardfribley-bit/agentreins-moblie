package com.wangzan101.mika.core.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
