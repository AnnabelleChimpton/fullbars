pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // RootEncoder's standalone RTMP(S) client (Apache-2.0). Restricted so JitPack can't serve anything else.
        maven("https://jitpack.io") { content { includeGroup("com.github.pedroSG94.RootEncoder") } }
    }
}

rootProject.name = "irlsupreme-android"

// srtla-kt (Apache-2.0) is consumed as a composite build: "srtlakt:srtla-kt" is substituted with the local project.
includeBuild("../srtla-kt")

include(":engine", ":app", ":twitch")
