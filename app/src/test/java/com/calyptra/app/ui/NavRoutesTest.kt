package com.calyptra.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRoutesTest {

    @Test
    fun `home is the only unprotected destination`() {
        assertFalse(NavRoutes.isPinProtected(NavRoutes.HOME))
        assertFalse(NavRoutes.isPinProtected(null))
    }

    @Test
    fun `every destination behind the settings door is protected`() {
        for (route in listOf(
            NavRoutes.SETTINGS, NavRoutes.WHITELIST, NavRoutes.ALLOWLIST, NavRoutes.TIMELINE
        )) {
            assertTrue("$route must close when the grace session ends", NavRoutes.isPinProtected(route))
        }
    }
}
