package com.hipay.golden

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Guard the dual source of truth`.json` golden files
 * (commonTest/resources/golden) and their Kotlin mirror (GoldenFiles.kt, which
 * exists because Kotlin/Native test binaries cannot read classpath resources).
 *
 * Runs on the JVM android host test (which CAN read the files) and asserts each
 * `.json` is structurally equal to its `GOLDEN_*` constant via the same parity
 * harness — so the two cannot silently drift.
 */
class GoldenMirrorTest {

    @Test
    fun jsonFilesMatchTheirKotlinMirror() {
        val workingDir = System.getProperty("user.dir") ?: "."
        val dir = locateGoldenDir() ?: fail("golden resources dir not found from $workingDir")
        val pairs = listOf(
            "token_create_request.json" to GOLDEN_TOKEN_CREATE_REQUEST,
            "token_create_response.json" to GOLDEN_TOKEN_CREATE_RESPONSE,
            "order_request.json" to GOLDEN_ORDER_REQUEST,
            "order_response.json" to GOLDEN_ORDER_RESPONSE,
        )
        for ((fileName, mirror) in pairs) {
            val file = File(dir, fileName)
            if (!file.isFile) fail("missing golden file: $fileName")
            // assertJsonParity(expected = file text, actual = parsed mirror constant)
            assertJsonParity(file.readText(), parseJson(mirror))
        }
    }

    private fun parseJson(text: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(text)

    /** Walks up from the working dir to find commonTest/resources/golden. */
    private fun locateGoldenDir(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(5) {
            val candidate = File(dir, "hipayfullservice/src/commonTest/resources/golden")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/commonTest/resources/golden")
            if (here.isDirectory) return here
            dir = dir?.parentFile ?: return null
        }
        return null
    }
}
