plugins {
    id("com.android.library")
}

android {
    namespace = "app.irlsupreme.engine"
    compileSdk = 36
    buildToolsVersion = "36.1.0"
    defaultConfig {
        minSdk = 31
        // On-device encoder, audio and GL checks (src/androidTest), run by hand on a phone before a release. The
        // platform's own JUnit 3 runner, so they need no test libraries beyond the SDK's.
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Only RootEncoder's RTMP(S) protocol client. Capture, compositing, encoding and recovery are ours: owning the
    // clock and the recovery path is what keeps the stream from stalling.
    implementation(libs.rootencoder.rtmp)
    implementation(libs.rootencoder.srt)
    // Bonding (Apache-2.0, composite build from ../srtla-kt)
    implementation("srtlakt:srtla-kt")
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
