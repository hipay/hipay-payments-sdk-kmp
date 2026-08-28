package com.hipay.core.threeds

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

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

    // A challenge that cannot be presented must be answered, never left silent: the caller is awaiting
    // an already-authorized payment, so silence would hang it for the life of the process — and the
    // session the platform refuses to build is a nil the binding would turn into a crash. Presenting a
    // real challenge needs a device; refusing to present one does not.
    @Test
    fun aUrlNoSessionCanPresentIsAnsweredInsteadOfHanging() {
        listOf("ftp://example.invalid/challenge", "", "not a url", "hipaydemo://x").forEach { url ->
            var answers = 0
            var captured: String? = "unset"

            defaultThreeDSLauncher().launchInApp(url, "hipaydemo") {
                answers++
                captured = it
            }

            assertEquals(1, answers, "expected exactly one answer for '$url'")
            assertNull(captured)
        }
    }
}
