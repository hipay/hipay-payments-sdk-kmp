import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android Jetpack Compose card-entry module (story 7.1 enabler; component is 7.2).
// The Android mirror of the iOS HiPayCard SPM product: a classic com.android.library
// + Compose module depending on the headless :hipayfullservice KMP core. NOT Compose
// Multiplatform, NOT in the KMP module's androidMain (D14/D1).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.mavenPublish)
}

// Coordinates so a consumer (the Android demo, via composite build) can resolve this
// module as "com.hipay.payments:card"
group = "com.hipay.payments"
// version: single source from gradle.properties — inherited as project.version.

android {
    // com.hipay.card keeps this module on the PCI anti-logging path (scripts/check-no-logging.sh).
    namespace = "com.hipay.card"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // The headless KMP core (shared validation/i18n contract). Consumed unchanged (D14).
    // `api` so consumers of :hipaycard (the demo) also get the core API (GatewayClient,
    // CallbackUrlParser, HiPayConfig, OrderRequest…) transitively.
    api(project(":hipayfullservice"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // Chrome Custom Tabs: SDK-managed 3DS presentation (story 11.13). Browser/UI dep stays
    // on :hipaycard only — the headless KMP core (:hipayfullservice) remains UI/browser-free.
    implementation(libs.androidx.browser)
    // Jetpack DataStore: encrypted saved-card blob storage. Stays on the
    // card module — the headless core has no storage dep.
    implementation(libs.androidx.datastore.preferences)

    // Instrumented Compose UI-test harness (story 7.1).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Force the post-fix espresso/runner (Compose ui-test pulls 3.5.0/1.5.0 transitively,
    // which crash on API 34+ via InputManager.getInstance).
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    // Provides the empty ComponentActivity host for createComposeRule().
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Publication (story 8.1): the Android card module ships to Maven as
// com.hipay.payments:card; its POM declares the core dependency (via `api`).
// POM kept in sync with :hipayfullservice (license is a documented TODO(legal)).
mavenPublishing {
    publishToMavenCentral()
    // Sign only on the gated release path (keyless publishToMavenLocal must work).
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
    coordinates(group.toString(), "card", version.toString())
    pom {
        name = "HiPay Payments SDK — Android card UI"
        description = "Jetpack Compose card-entry component for the HiPay payment SDK (Android)."
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
