package dev.dshn.app.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentCoordinatorPolicyTest {

    @Test
    fun autoStartsWhenOnboardingCompletedDuringExtraction() {
        assertTrue(
            shouldAutoStartAfterBundledInstall(
                wasRunning = false,
                autoStartIfReady = false,
                onboardingCompleted = true,
                hasApiKey = true,
                runtimeAlreadyUsable = false
            )
        )
    }

    @Test
    fun doesNotAutoStartBeforeSetupCompletedOrWithoutApiKey() {
        assertFalse(
            shouldAutoStartAfterBundledInstall(
                wasRunning = false,
                autoStartIfReady = false,
                onboardingCompleted = false,
                hasApiKey = true,
                runtimeAlreadyUsable = false
            )
        )
        assertFalse(
            shouldAutoStartAfterBundledInstall(
                wasRunning = true,
                autoStartIfReady = true,
                onboardingCompleted = true,
                hasApiKey = false,
                runtimeAlreadyUsable = false
            )
        )
        assertFalse(
            shouldAutoStartAfterBundledInstall(
                wasRunning = true,
                autoStartIfReady = true,
                onboardingCompleted = true,
                hasApiKey = true,
                runtimeAlreadyUsable = true
            )
        )
    }
}
