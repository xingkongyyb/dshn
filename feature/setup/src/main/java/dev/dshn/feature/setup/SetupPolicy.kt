package dev.dshn.feature.setup

import dev.dshn.app.data.AppSettings

/**
 * Decides whether first-run setup should be shown on cold start.
 * Returns `(setupRequired, shouldMarkOnboardingCompleted)`.
 */
fun shouldRequireInitialSetup(
    settings: AppSettings,
    hasApiKey: Boolean,
    rootfsReady: Boolean
): Pair<Boolean, Boolean> {
    if (settings.onboardingCompleted) return false to false
    // If the user already started stepping through the wizard and the process died mid-wizard,
    // keep showing the remaining steps instead of silently skipping them.
    if (settings.setupWizardStarted) return true to false
    if (hasApiKey && rootfsReady) return false to true
    return true to false
}
