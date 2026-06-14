import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android Jetpack Compose card-entry module (story 7.1 enabler; component is 7.2).
// The Android mirror of the iOS HiPayCard SPM product: a classic com.android.library
// + Compose module depending on the headless :hipayfullservice KMP core. NOT Compose
// Multiplatform, NOT in the KMP module's androidMain (D14/D1).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

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
    implementation(project(":hipayfullservice"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)

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
