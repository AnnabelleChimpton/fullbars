// Top-level build file.
// AGP 9 has built-in Kotlin support: the module does NOT apply org.jetbrains.kotlin.android.
// Declaring the Kotlin Gradle plugin here (apply false) only pins the KGP version AGP uses (default is older).
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("com.android.library") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
