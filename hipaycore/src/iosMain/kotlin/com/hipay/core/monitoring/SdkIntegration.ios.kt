package com.hipay.core.monitoring

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.timeZoneWithAbbreviation

@OptIn(ExperimentalForeignApi::class)
internal actual fun sha256Hex(input: String): String {
    val bytes = input.encodeToByteArray()
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    // An empty array cannot be pinned; the length passed is still the real one.
    val pinnable = if (bytes.isEmpty()) ByteArray(1) else bytes
    pinnable.usePinned { source ->
        digest.usePinned { target ->
            CC_SHA256(source.addressOf(0), bytes.size.convert(), target.addressOf(0))
        }
    }
    return digest.joinToString("") { byte -> byte.toString(16).padStart(2, '0') }
}

internal actual fun utcTimestamp(): String = NSDateFormatter().apply {
    dateFormat = TIMESTAMP_FORMAT
    // A fixed format needs a fixed locale, or a non-Gregorian calendar reformats it.
    locale = NSLocale("en_US_POSIX")
    NSTimeZone.timeZoneWithAbbreviation("UTC")?.let { timeZone = it }
}.stringFromDate(NSDate())

internal actual fun randomUuid(): String = NSUUID().UUIDString

private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

/** The host application's bundle identifier. */
internal actual fun hostDomain(): String? =
    platform.Foundation.NSBundle.mainBundle.bundleIdentifier
