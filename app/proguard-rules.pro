# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile


# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# DJI SDK
-keep class com.dji.** { *; }
-keep class com.secneo.** { *; }
-keep class dji.** { *; }
-dontwarn dji.**
-dontwarn com.dji.**
-dontwarn com.secneo.**

# DJI UX SDK
-keep class dji.ux.** { *; }
-dontwarn dji.ux.**

# Prevent obfuscation of DJI annotations
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# TensorFlow Lite
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Retrofit + Gson
-keep class com.minedetector.network.models.** { *; }
-keepattributes SerializedName
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Room entities
-keep class com.minedetector.data.local.entities.** { *; }

# Mapbox
-keep class com.mapbox.** { *; }
-dontwarn com.mapbox.**