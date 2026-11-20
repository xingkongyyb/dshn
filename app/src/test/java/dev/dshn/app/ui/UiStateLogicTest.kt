package dev.dshn.app.ui

import dev.dshn.app.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateLogicTest {

    @Test
    fun pushUniqueRouteDeduplicatesAcrossFullStack() {
        val initial = listOf(
            AppRoute.CHAT,
            AppRoute.SETTINGS,
            AppRoute.SETTINGS_ENVIRONMENT,
            AppRoute.SETTINGS_MODEL
        )
        // Re-navigating to an existing deeper route truncates back to it instead of duplicating
        val truncated = pushUniqueRoute(initial, AppRoute.SETTINGS_ENVIRONMENT)
        assertEquals(
            listOf(AppRoute.CHAT, AppRoute.SETTINGS, AppRoute.SETTINGS_ENVIRONMENT),
            truncated
        )

        // Pushing a fresh route appends cleanly
        val pushed = pushUniqueRoute(truncated, AppRoute.SETTINGS_DIAGNOSTICS)
        assertEquals(
            listOf(AppRoute.CHAT, AppRoute.SETTINGS, AppRoute.SETTINGS_ENVIRONMENT, AppRoute.SETTINGS_DIAGNOSTICS),
            pushed
        )

        // Navigating to top route is a no-op
        assertEquals(pushed, pushUniqueRoute(pushed, AppRoute.SETTINGS_DIAGNOSTICS))

        // Navigating to CHAT resets to home
        assertEquals(listOf(AppRoute.CHAT), pushUniqueRoute(pushed, AppRoute.CHAT))
    }

    @Test
    fun shouldRequireInitialSetupDistinguishesMidWizardDeathFromPreWizardUpgrade() {
        // Pre-wizard user: already has key + rootfs, never started wizard -> skip & mark completed
        val (reqPre, markPre) = shouldRequireInitialSetup(
            settings = AppSettings(onboardingCompleted = false, setupWizardStarted = false),
            hasApiKey = true,
            rootfsReady = true
        )
        assertFalse(reqPre)
        assertTrue(markPre)

        // Mid-wizard process death: user entered key while rootfs ready, died before Permission/Background
        val (reqMid, markMid) = shouldRequireInitialSetup(
            settings = AppSettings(onboardingCompleted = false, setupWizardStarted = true, setupWizardStep = 3),
            hasApiKey = true,
            rootfsReady = true
        )
        assertTrue(reqMid)
        assertFalse(markMid)

        // Already completed onboarding -> never show again
        val (reqDone, markDone) = shouldRequireInitialSetup(
            settings = AppSettings(onboardingCompleted = true, setupWizardStarted = true),
            hasApiKey = false,
            rootfsReady = false
        )
        assertFalse(reqDone)
        assertFalse(markDone)
    }

    @Test
    fun parseHostPortHandlesValidAndMalformedEndpoints() {
        assertEquals("127.0.0.1" to 5555, parseHostPort("127.0.0.1:5555"))
        assertEquals("192.168.1.42" to 41234, parseHostPort(" 192.168.1.42:41234 "))
        assertEquals("::1" to 5555, parseHostPort("[::1]:5555"))

        // Malformed inputs must return port = -1 so validation error is surfaced
        assertEquals(-1, parseHostPort("127.0.0.1: 5555").second)
        assertEquals(-1, parseHostPort("fe80::1:5555").second)
        assertEquals(-1, parseHostPort("127.0.0.1:abc").second)
        assertEquals(-1, parseHostPort("127.0.0.1:").second)
        assertEquals(-1, parseHostPort(":5555").second)
        assertEquals(-1, parseHostPort("localhost").second)
    }
}
