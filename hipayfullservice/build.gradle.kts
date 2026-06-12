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
version = "1.0.0"

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

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "fullservice-kmp", version.toString())

    pom {
        name = "HiPay Fullservice KMP SDK"
        description = "HiPay Fullservice payment SDK for Kotlin Multiplatform (card payment)."
        inceptionYear = "2026"
        url = "https://github.com/hipay/hipay-fullservice-kmp/"
        licenses {
            license {
                name = "XXX"
                url = "YYY"
                distribution = "ZZZ"
            }
        }
        developers {
            developer {
                id = "XXX"
                name = "YYY"
                url = "ZZZ"
            }
        }
        scm {
            url = "XXX"
            connection = "YYY"
            developerConnection = "ZZZ"
        }
    }
}
