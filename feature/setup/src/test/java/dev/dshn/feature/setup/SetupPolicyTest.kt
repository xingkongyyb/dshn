package dev.dshn.feature.setup

import dev.dshn.app.data.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPolicyTest {

    @Test
    fun shouldRequireInitialSetupDistinguishesMidWizardDeathFromPreWizardUpgrade() {
        val (reqPre, markPre) = shouldRequireInitialSetup(
            settings = AppSettings(onboardingCompleted = false, setupWizardStarted = false),
            hasApiKey = true,
            rootfsReady = true
        )
        assertFalse(reqPre)
        assertTrue(markPre)

        val (reqMid, markMid) = shouldRequireInitialSetup(
            settings = AppSettings(onboardingCompleted = false, setupWizardStarted = true, setupWizardStep = 3),
            hasApiKey = true,
            rootfsReady = true
        )
        assertTrue(reqMid)
        assertFalse(markMid)

        val (reqDone, markDone) = shouldRequireInitialSetup(
            settings = AppSettings(onboardingCompleted = true, setupWizardStarted = true),
            hasApiKey = false,
            rootfsReady = false
        )
        assertFalse(reqDone)
        assertFalse(markDone)
    }
}
