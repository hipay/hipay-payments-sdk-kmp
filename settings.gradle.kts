pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "hipay-payments-sdk-kmp"
include(":hipayfullservice")
include(":hipaycard")
include(":hipaycard-cmp")
