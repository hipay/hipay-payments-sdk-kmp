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

group = "com.hipay.fullservice"
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
            // Core types for the public commonMain contract (HiPayConfig, CardNetwork,
            // Transaction, CustomerInfo). `api` because these types appear in the public
            // expect API → a common/iOS consumer needs the core on its compile classpath
            // (Android also gets it via api(:hipaycard), but common/iOS have no such path).
            api(project(":hipayfullservice"))
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
// com.hipay.fullservice:hipaycard-cmp; its POM declares :hipaycard (via `api`).
mavenPublishing {
    publishToMavenCentral()
    // Sign only on the gated release path (keyless publishToMavenLocal must work).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(group.toString(), "hipaycard-cmp", version.toString())
    pom {
        name = "HiPay Fullservice — Compose-Multiplatform card UI"
        description = "Shared Compose-Multiplatform card-entry component for the HiPay Fullservice SDK."
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
