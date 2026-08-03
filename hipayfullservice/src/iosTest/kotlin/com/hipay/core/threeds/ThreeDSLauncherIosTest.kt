package com.hipay.core.threeds

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame

class ThreeDSLauncherIosTest {

    // Constructing the iOS launcher must not require a presented UI: the payment entry point builds it
    // before the sheet exists. Presenting a real challenge needs a device, so that stays out of scope
    // here — this pins the part that can regress silently.
    @Test
    fun defaultLauncherIsAvailableWithoutAnyUi() {
        assertNotNull(defaultThreeDSLauncher())
    }

    // Two payments must not share one session holder: a second challenge would cancel the first.
    @Test
    fun eachCallReturnsItsOwnLauncher() {
        val first = defaultThreeDSLauncher()
        val second = defaultThreeDSLauncher()

        assertNotNull(first)
        assertNotNull(second)
        assertNotSame(first, second)
    }
}
