import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "com.hipay.fullservice"
// version: single source from gradle.properties — inherited as project.version.

kotlin {
    androidLibrary {
        namespace = "com.hipay.fullservice"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withJava() // enable java compilation support
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }

        compilations.configureEach {
            compilerOptions.configure {
                jvmTarget.set(
                    JvmTarget.JVM_11
                )
            }
        }
    }
    val xcf = XCFramework("HiPayFullservice")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "HiPayFullservice"
            // The default bundle ID is derived from the module name and is a
            // known App Store validation/collision source for shipped SDKs.
            binaryOption("bundleId", "com.hipay.fullservice")
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// PCI anti-logging gate (story 2.4): fails the build on any logging primitive
// under com.hipay.card or swift/Sources/HiPayCard.
val checkCardNoLogging = tasks.register<Exec>("checkCardNoLogging") {
    group = "verification"
    description = "Asserts zero logging on the card path (PCI)"
    commandLine("bash", rootDir.resolve("scripts/check-no-logging.sh").absolutePath)
}
tasks.named("check") { dependsOn(checkCardNoLogging) }

// i18n key-parity gate (story 5.2): every CardEntryStringKey constant must have
// a value in each locale catalog (iOS FR/EN/IT; Android added in 7.3).
val checkI18nParity = tasks.register<Exec>("checkI18nParity") {
    group = "verification"
    description = "Asserts every CardEntryStringKey has a value in all locale catalogs"
    commandLine("bash", rootDir.resolve("scripts/check-i18n-parity.sh").absolutePath)
}
tasks.named("check") { dependsOn(checkI18nParity) }

mavenPublishing {
    publishToMavenCentral()

    // Sign only on the gated release path (story 8.1): the CI release job provides
    // `ORG_GRADLE_PROJECT_signingInMemoryKey`; a keyless local `publishToMavenLocal`
    // (dev / POM validation) must NOT require signing.
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }

    coordinates(group.toString(), "fullservice-kmp", version.toString())

    pom {
        name = "HiPay Fullservice KMP SDK"
        description = "HiPay Fullservice payment SDK for Kotlin Multiplatform (card payment)."
        inceptionYear = "2026"
        // TODO(repo): final repository URL not yet decided — see architecture-repos.md
        // (§9, deferred: final repo names + GitLab/GitHub topology). Interim value.
        url = "https://github.com/hipay/hipay-fullservice-kmp"
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
            // TODO(repo): interim — final SCM URL pending architecture-repos.md (§9).
            url = "https://github.com/hipay/hipay-fullservice-kmp"
            connection = "scm:git:https://github.com/hipay/hipay-fullservice-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/hipay/hipay-fullservice-kmp.git"
        }
    }
}
