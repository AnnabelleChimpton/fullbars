import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Twitch Client ID comes from android/local.properties (`twitch.clientId=...`), never from source control.
// Client IDs are public (Twitch docs), but keeping it out of git lets forks register their own app.
// Read through a provider so the configuration cache tracks the file.
val twitchClientId: String = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text ->
        Properties().apply { load(text.reader()) }.getProperty("twitch.clientId", "").trim()
    }
    .orElse("")
    .get()

// A release APK without a client ID would ship a sign-in button that can only fail. Debug builds keep the blank
// fallback (the app hides sign-in and uses a stream key), so forks and CI can build and test without one.
// Checked against the requested task names, so configuring the project, unit tests and lint never need the ID.
val buildsReleaseArtifact: Boolean = gradle.startParameter.taskNames.any { requested ->
    val name = requested.substringAfterLast(':')
    name == "assemble" || name == "build" ||
        Regex("^(assemble|bundle|install|package|publish)\\w*Release\\w*$").matches(name)
}
if (buildsReleaseArtifact && twitchClientId.isBlank()) {
    throw GradleException(
        "Release builds need a Twitch client ID: add twitch.clientId=<your client id> to android/local.properties " +
            "(register an app at dev.twitch.tv with client type Public).",
    )
}

android {
    namespace = "app.irlsupreme.twitch"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        minSdk = 31
        buildConfigField("String", "TWITCH_CLIENT_ID", "\"${twitchClientId.replace("\"", "")}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.coroutines.android)
    api(libs.okhttp) // OkHttpClient appears in public constructors
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
}
