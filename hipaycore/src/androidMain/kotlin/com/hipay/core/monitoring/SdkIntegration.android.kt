package com.hipay.core.monitoring

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal actual fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.encodeToByteArray())
        .joinToString("") { byte -> ((byte.toInt() and 0xff) + 0x100).toString(16).substring(1) }

internal actual fun utcTimestamp(): String =
    SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

internal actual fun randomUuid(): String = UUID.randomUUID().toString()

// SimpleDateFormat rather than java.time: minSdk is 24 and this needs no desugaring.
private const val TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"

/**
 * The process name from `/proc/self/cmdline`, which is the application id for an app's main process.
 * Read this way because the core holds no `Context` — and must not, being the headless module. A
 * secondary process reports its `applicationId:suffix`; the suffix is dropped so both attribute to
 * the same application.
 */
internal actual fun hostDomain(): String? = runCatching {
    java.io.File("/proc/self/cmdline").readText()
        .substringBefore(NUL)
        .substringBefore(':')
        .trim()
        .ifEmpty { null }
}.getOrNull()

/** `cmdline` is NUL-terminated. */
private const val NUL = '\u0000'
