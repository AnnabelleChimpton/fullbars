# R8 rules for the release build.
#
# Not in use yet: isMinifyEnabled stays false in app/build.gradle.kts until a minified release has passed a hands-on
# smoke test (bonding, the Pocket 3, a helper phone, a web overlay, chat and alerts). R8 failures show up only at run
# time, and only on the paths that use reflection, so these rules lean towards keeping too much.
#
# Libraries that ship their own consumer rules (OkHttp, kotlinx.serialization, coroutines, AndroidX, Compose) need
# nothing here.

# Readable crash reports: keep line numbers, hide the source file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# RootEncoder (com.pedro): the RTMP(S) and SRT clients. It reflects in places and ships no consumer rules.
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**

# kotlinx.serialization: every DTO is decoded with an explicit serializer(), which the library's own rules cover.
# Keep the generated serializers anyway, in case a later call uses serializer<T>() lookups.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

# Enum names are saved in settings (quality, orientation, scene) and read back with valueOf or by name: renaming one
# would quietly reset a setting after an update.
-keepclassmembers enum app.irlsupreme.**, srtlakt.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The pairing QR scanner (zxing-android-embedded) starts its CaptureActivity from the library's manifest.
-keep class com.journeyapps.barcodescanner.** { *; }
-dontwarn com.google.zxing.**

# CameraHotspot looks up SoftApConfiguration.Builder methods by name; those are framework classes, which R8 never
# renames, so they need no rule.
