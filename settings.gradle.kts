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
include(":hipaycore")
include(":hipaycard")
include(":hipaycard-cmp")
include(":hipay-applepay-cmp")
