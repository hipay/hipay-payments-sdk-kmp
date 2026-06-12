package com.hipay.core

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PingTest {

    @Test
    fun pingReturnsCommonMainValue() = runTest {
        assertEquals("pong from Kotlin commonMain", ping())
    }
}
