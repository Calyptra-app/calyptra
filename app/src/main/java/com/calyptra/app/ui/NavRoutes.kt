package com.calyptra.app.ui

/**
 * Navigation routes. Every destination other than [HOME] sits behind the
 * PIN-gated settings door, and its contents assume the parent passed that
 * gate in the current grace session.
 */
object NavRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val WHITELIST = "whitelist"
    const val ALLOWLIST = "allowlist"
    const val TIMELINE = "timeline"

    private val PIN_PROTECTED = setOf(SETTINGS, WHITELIST, ALLOWLIST, TIMELINE)

    /** True when [route] was reached through the PIN gate. Backgrounding the
     *  app ends the grace session (FR-10.6), so such a destination must be
     *  closed too; otherwise a screen left open (e.g. the whitelist, whose
     *  edits are not individually gated) stays editable without the PIN. */
    fun isPinProtected(route: String?): Boolean = route in PIN_PROTECTED
}
