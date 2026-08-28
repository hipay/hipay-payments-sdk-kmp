import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Opt-in Compose-Multiplatform Apple Pay button. A CMP merchant adds THIS
// artifact only if they want Apple Pay — merchants who don't depend on it pull no Apple Pay code
// (modularity, Option A). Apple Pay is iOS-only: the iOS actual renders the native PKPaymentButton
// via UIKitView; the Android actual renders nothing (Apple Pay is unavailable on Android).
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
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The shared Apple Pay appearance enums (HiPayApplePayButtonStyle/Type) live in the core.
            api(project(":hipaycore"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    // com.hipay.card.* keeps this module on the PCI anti-logging path (scripts/check-no-logging.sh).
    namespace = "com.hipay.card.applepay.cmp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Publication: the CMP Apple Pay module ships to Maven as
// com.hipay.payments:applepay-cmp; its POM declares :hipaycore (via `api`).
mavenPublishing {
    publishToMavenCentral()
    // Sign only on the gated release path (keyless publishToMavenLocal must work).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(group.toString(), "applepay-cmp", version.toString())
    pom {
        name = "HiPay Fullservice — Compose-Multiplatform Apple Pay button"
        description = "Opt-in Compose-Multiplatform Apple Pay button (iOS) for the HiPay Fullservice SDK."
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
