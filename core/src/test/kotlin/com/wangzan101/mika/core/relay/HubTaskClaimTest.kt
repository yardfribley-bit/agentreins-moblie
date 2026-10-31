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
}
