pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "srtla-kt"

// Desktop/container CLI for testing against a real srtla_rec. Not part of the library.
include(":cli")
