# Keep annotations for reflection
-keepattributes *Annotation*

# Gson — only keep the core Gson class + TypeToken (for reflection).
# Let R8 shrink all unused Gson adapters/internals. Only serialized
# model classes need to be kept (via @SerializedName or explicit rules).
-keep class com.google.gson.Gson { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep the app's own serialized model classes (used by Gson reflection)
-keep class com.nin0dev.vendroid.utils.UpdateData { *; }

# Volley is NOT used in this project — remove dead keep rules.
# (Removed: -keep class com.android.volley.** { *; })

# Suppress warnings for javax.annotation (not on Android)
-dontwarn javax.annotation.**

# Keep source file names and line numbers for crash reports in debug
-keepattributes SourceFile,LineNumberTable

# Aggressive R8 optimization — enables inlining, class merging,
# access modification, and multiple optimization passes for a
# significantly smaller DEX with faster class loading at runtime.
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5
-mergeinterfacesaggressively

# Remove ALL logging in release (including Log.w and Log.e which
# still allocate strings for their arguments even if not visible).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int wtf(...);
    public static int e(...);
}
