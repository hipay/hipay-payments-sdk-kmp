import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Optional Compose-Multiplatform card-entry component (Epic 10, story 10.1).
// A CMP merchant calls one @Composable HiPayCardEntry(...) from commonMain.
// Design (i): Android delegates to the native :hipaycard; iOS renders in Compose-MP
// (placeholder here — story 10.2). This is the ONLY module carrying the Compose-MP
// dependency — native merchants (:hipaycard / HiPayCard) are unaffected (D14/D1).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.hipay.payments"
// version: single source from gradle.properties — inherited as project.version.

kotlin {
    androidTarget {
        compilations.configureEach {
            compilerOptions.configure { jvmTarget.set(JvmTarget.JVM_11) }
        }
    }
    iosArm64()
    iosSimulatorArm64 {
        // Keychain access for the bare Keychain-store test process: on the simulator, securityd
        // reads entitlements from the binary's __entitlements section (an application-identifier
        // is required, else SecItem* fails with errSecMissingEntitlement). Injecting them via
        // codesign instead trips launchd's spawn security policy — link-time section it is.
        binaries.getTest("DEBUG").linkerOpts(
            "-sectcreate", "__TEXT", "__entitlements",
            layout.projectDirectory.file("keychain-test.entitlements").asFile.absolutePath,
        )
    }

    sourceSets {
        commonMain.dependencies {
            // Core types for the public commonMain contract (HiPayConfig, CardNetwork,
            // Transaction, CustomerInfo). `api` because these types appear in the public
            // expect API → a common/iOS consumer needs the core on its compile classpath
            // (Android also gets it via api(:hipaycard), but common/iOS have no such path).
            api(project(":hipaycore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // Bundled brand icons for the network chips (story 11.4) — Res.drawable.hp_*.
            implementation(compose.components.resources)
        }
        androidMain.dependencies {
            // The native Android component the Android actual delegates to. `api` so the
            // published POM declares it (the actual needs it at runtime); it transitively
            // exposes the core too.
            api(project(":hipaycard"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The Keychain-backed store tests need a REAL booted simulator: the default standalone spawn
// runs without securityd, so every SecItem* call fails with errSecNotAvailable (-25291).
// Boot any iOS simulator first (`xcrun simctl boot <device>`); the task attaches to it.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
    device.set("booted")
    doFirst {
        // Fail fast with an actionable message instead of an opaque simctl attach error.
        val booted = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted")
            .redirectErrorStream(true).start()
            .let { process ->
                val output = process.inputStream.readBytes().decodeToString()
                process.waitFor()
                output
            }
        check(booted.contains("(Booted)")) {
            "No booted iOS simulator — these tests attach to a running device (securityd is " +
                "unavailable to standalone-spawned processes). Boot one first: " +
                "`xcrun simctl boot <device>` (any iPhone simulator works)."
        }
    }
}

android {
    // com.hipay.card.cmp keeps this module on the PCI anti-logging path
    // (scripts/check-no-logging.sh).
    namespace = "com.hipay.card.cmp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Publication (Epic 10): the CMP card module ships to Maven as
// com.hipay.payments:card-cmp; its POM declares :hipaycard (via `api`).
// The Compose resources accessor package defaults to "<group>.<module>.generated.resources", which
// tied a SOURCE package to a PUBLICATION coordinate — renaming the group broke every import. Pinned
// here so the two can move independently.
compose.resources {
    packageOfResClass = "com.hipay.card.cmp.resources"
}

mavenPublishing {
    publishToMavenCentral()
    // Sign only on the gated release path (keyless publishToMavenLocal must work).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(group.toString(), "card-cmp", version.toString())
    pom {
        name = "HiPay Payments SDK — Compose-Multiplatform card UI"
        description = "Shared Compose-Multiplatform card-entry component for the HiPay Fullservice SDK."
        inceptionYear = "2026"
        url = "https://github.com/hipay/hipay-payments-sdk-kmp"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "hipay"
                name = "HiPay"
                url = "https://github.com/hipay"
            }
        }
        scm {
            url = "https://github.com/hipay/hipay-payments-sdk-kmp"
            connection = "scm:git:https://github.com/hipay/hipay-payments-sdk-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/hipay/hipay-payments-sdk-kmp.git"
        }
    }
}
