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

rootProject.name = "hipay-fullservice-kmp"
include(":hipayfullservice")
include(":hipaycard")
include(":hipaycard-cmp")
include(":hipay-applepay-cmp")
