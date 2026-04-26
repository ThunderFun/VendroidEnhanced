# Keep annotations for reflection
-keepattributes *Annotation*

# Gson - keep classes used for JSON serialization/reflection
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Volley
-dontwarn com.android.volley.**
-keep class com.android.volley.** { *; }

# Suppress warnings for javax.annotation (not on Android)
-dontwarn javax.annotation.**

# Keep source file names and line numbers for crash reports in debug
-keepattributes SourceFile,LineNumberTable

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
