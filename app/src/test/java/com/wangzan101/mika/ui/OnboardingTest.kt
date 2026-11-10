package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingTest {
    private fun inputs(
        hasMain: Boolean = false,
        modelDone: Boolean = false,
        phoneNeeded: Boolean = true,
        phoneSeen: Boolean = false,
        vaultChosen: Boolean = false,
        vaultSeen: Boolean = false,
        sampleSeen: Boolean = false,
    ) = Onboarding.Inputs(hasMain, modelDone, phoneNeeded, phoneSeen, vaultChosen, vaultSeen, sampleSeen)

    @Test fun freshInstallWalksWelcomeModelPhoneVaultThenSample() {
        val i = inputs()
        assertEquals(OnboardingStep.Welcome, Onboarding.start(i))
        assertEquals(OnboardingStep.Model, Onboarding.next(OnboardingStep.Welcome, i))
        assertEquals(OnboardingStep.Phone, Onboarding.next(OnboardingStep.Model, i))
        assertEquals(OnboardingStep.Vault, Onboarding.next(OnboardingStep.Phone, i))
        assertEquals(OnboardingStep.Sample, Onboarding.next(OnboardingStep.Vault, i))
        assertNull(Onboarding.next(OnboardingStep.Sample, i))
    }

    @Test fun configuredModelSkipsWelcomeAndModel() {
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs(hasMain = true)))
        assertEquals(OnboardingStep.Vault, Onboarding.start(inputs(hasMain = true, phoneSeen = true)))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneNeeded = false, vaultChosen = true)))
    }

    @Test fun modelSetupDoneOnceIsNotRepeatedEvenIfModelsWereRemoved() {
        assertEquals(OnboardingStep.Phone, Onboarding.start(inputs(modelDone = true)))
        assertEquals(OnboardingStep.Vault, Onboarding.start(inputs(modelDone = true, phoneSeen = true)))
    }

    @Test fun phoneStepSkippedWhenReadyOrSeen() {
        assertEquals(OnboardingStep.Vault, Onboarding.next(OnboardingStep.Model, inputs(phoneNeeded = false)))
        assertEquals(OnboardingStep.Vault, Onboarding.next(OnboardingStep.Model, inputs(phoneSeen = true)))
    }

    @Test fun chosenOrSeenVaultSkipsToSampleOnlyAfterAStep() {
        val ready = inputs(vaultChosen = true)
        assertEquals(OnboardingStep.Sample, Onboarding.next(OnboardingStep.Phone, ready))
        assertNull(Onboarding.start(inputs(hasMain = true, phoneSeen = true, vaultChosen = true)))
        assertNull(Onboarding.next(OnboardingStep.Phone, inputs(vaultSeen = true, sampleSeen = true)))
    }

    @Test fun dotsFollowSteps() {
        assertEquals(listOf(0, 1, 2, 3, 4), OnboardingStep.entries.map(Onboarding::dot))
        assertEquals(5, Onboarding.DOTS)
    }
}
