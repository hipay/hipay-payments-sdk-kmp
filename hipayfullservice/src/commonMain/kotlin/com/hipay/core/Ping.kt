package com.hipay.core

// Walking-skeleton probe (architecture D10): proves the Kotlin -> XCFramework ->
// SPM -> Swift `try await` chain before any business logic. Removed once the
// real API lands (story 2.1+).
suspend fun ping(): String = "pong from Kotlin commonMain"
