import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. The keystore and its passwords never live in the repo: a properties file outside it holds
//   storeFile=/absolute/path/to/fullbars-release.jks   (or relative to the properties file)
//   storePassword=…
//   keyAlias=…
//   keyPassword=…
// and its path comes from the Gradle property fullbars.keystoreProperties (e.g. in ~/.gradle/gradle.properties) or
// the FULLBARS_KEYSTORE_PROPERTIES environment variable. Without it the build still configures (debug builds and
// tests don't need a key); only packaging a release stops, with a message saying what to set.
val keystorePropertiesFile: File? = providers.gradleProperty("fullbars.keystoreProperties")
    .orElse(providers.environmentVariable("FULLBARS_KEYSTORE_PROPERTIES"))
    .orNull?.trim()?.takeIf { it.isNotEmpty() }?.let { rootDir.resolve(it) } // relative: to android/

// Read through a provider so the configuration cache notices when the file changes.
val releaseKeystore: Properties? = keystorePropertiesFile?.takeIf { it.isFile }?.let { f ->
    providers.fileContents(objects.fileProperty().fileValue(f)).asText
        .map { text -> Properties().apply { load(text.reader()) } }.orNull
}
val signingKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")

/** Why release signing isn't set up, or null when it is. */
val releaseSigningProblem: String? = when {
    keystorePropertiesFile == null ->
        "Release signing isn't set up. Point the Gradle property fullbars.keystoreProperties (or the " +
            "FULLBARS_KEYSTORE_PROPERTIES environment variable) at a keystore properties file outside the repo. " +
            "See android/README.md."
    keystorePropertiesFile.canonicalFile.startsWith(rootDir.parentFile.canonicalFile) ->
        "The keystore properties file ($keystorePropertiesFile) is inside the repo. Move it, and the keystore, " +
            "outside the repo so neither can be committed."
    releaseKeystore == null -> "The keystore properties file wasn't found: $keystorePropertiesFile"
    signingKeys.any { releaseKeystore.getProperty(it).isNullOrBlank() } ->
        "The keystore properties file ($keystorePropertiesFile) needs " +
            signingKeys.filter { releaseKeystore.getProperty(it).isNullOrBlank() }.joinToString() + "."
    else -> null
}

android {
    namespace = "app.irlsupreme"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        // The app's old working name, kept on purpose: a new applicationId is a different app to Android, so existing
        // installs would neither update nor keep their settings.
        applicationId = "app.irlsupreme"
        minSdk = 31
        targetSdk = 36
        // Bumped in gradle.properties, one place for both.
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
    }

    buildFeatures { compose = true }

    signingConfigs {
        if (releaseSigningProblem == null && releaseKeystore != null && keystorePropertiesFile != null) create("release") {
            // A relative storeFile is relative to the properties file, so the pair can move together.
            storeFile = keystorePropertiesFile.parentFile.resolve(releaseKeystore.getProperty("storeFile"))
            storePassword = releaseKeystore.getProperty("storePassword")
            keyAlias = releaseKeystore.getProperty("keyAlias")
            keyPassword = releaseKeystore.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 stays off until a minified release passes a hands-on smoke test (bonding, Pocket 3, helper phone,
            // web overlay, chat and alerts): its failures only show at run time. The rules are ready for that day;
            // turn on isShrinkResources together with it.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Packaging an unsigned release would hand testers an APK they can't install: stop with the reason instead. Only the
// packaging steps check, so release unit tests and lint still run without a key.
val releaseSigningCheck = tasks.register("checkReleaseSigning") {
    val problem = releaseSigningProblem
    doLast { if (problem != null) throw GradleException(problem) }
}
tasks.configureEach {
    if (name == "packageRelease" || name == "signReleaseBundle") dependsOn(releaseSigningCheck)
}

dependencies {
    implementation(project(":engine"))
    implementation("srtlakt:srtla-kt") // Moblink, for helper mode
    implementation(project(":twitch"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp) // BRB server control API (pinned TLS)
    implementation(libs.zxing.embedded) // pairing QR scanner (Apache-2.0, no Play services)
    testImplementation(libs.junit)
}
