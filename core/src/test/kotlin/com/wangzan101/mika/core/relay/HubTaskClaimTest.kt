package com.wangzan101.mika.core.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class HubTaskClaimTest {
    private val ordinary = HubRemoteInstruction("ses", fromOwner = true, HubSensitiveAction.None)

    @Test fun flagOffDoesNothing() {
        assertEquals(HubClaimDecision.Disabled, HubTaskClaim.decide(false, ordinary))
    }

    @Test fun onlyTheSessionOwnerIsAccepted() {
        val other = ordinary.copy(fromOwner = false)
        assertEquals(HubClaimDecision.IgnoredNotOwner, HubTaskClaim.decide(true, other))
    }

    @Test fun sensitiveActionsAlwaysNeedThePerson() {
        for (action in listOf(
            HubSensitiveAction.Payment,
            HubSensitiveAction.RedPacket,
            HubSensitiveAction.Transfer,
            HubSensitiveAction.Deletion,
            HubSensitiveAction.ExternalMessage,
            HubSensitiveAction.PasswordEntry,
        )) {
            assertEquals(
                HubClaimDecision.NeedsUserConfirmation(action),
                HubTaskClaim.decide(true, ordinary.copy(action = action)),
            )
        }
    }

    @Test fun evenAPlainOwnerInstructionIsNotExecutedYet() {
        assertEquals(HubClaimDecision.NotImplemented, HubTaskClaim.decide(true, ordinary))
    }

    @Test fun onlyOwnerInstructionsAreKeptAndTheCursorAdvances() {
        val page = HubRelayPage(
            listOf(
                HubRelayEvent(1, "message", fromOwner = false, messageKind = "instruction"),
                HubRelayEvent(2, "message", fromOwner = true, messageKind = "progress"),
                HubRelayEvent(3, "message", fromOwner = true, messageKind = "instruction", text = "查一下 内存"),
                HubRelayEvent(4, "joined", fromOwner = true, messageKind = null),
            ),
            nextCursor = 4,
            hasMore = false,
        )
        assertEquals(emptyList<HubRelayEvent>(), HubInstructionFilter.take(false, page))
        assertEquals(listOf(3L), HubInstructionFilter.take(true, page).map { it.seq })
        val cursor = HubRelayCursor()
        assertEquals(listOf(3L), cursor.advance(true, page).map { it.seq })
        assertEquals(4L, cursor.after)
        assertEquals(20, HubInstructionFilter.waitSeconds(99))
        assertEquals(0, HubInstructionFilter.waitSeconds(-3))
    }
}
