# Keep annotations for reflection
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations

# WebView JS bridge — VencordNative must keep its class name, method names,
# and parameter types exactly as-is because JavaScript calls them by name
# via VencordMobileNative.getString(...) etc.  The default
# proguard-android-optimize.txt keeps @JavascriptInterface methods,
# but aggressive R8 settings (-repackageclasses, -allowaccessmodification)
# can still break the bridge if the class itself is obfuscated or merged.
-keep @interface android.webkit.JavascriptInterface
-keepclassmembers class com.nin0dev.vendroid.webview.VencordNative {
    @android.webkit.JavascriptInterface <methods>;
}
# Also keep the class from being renamed/merged so the runtime type
# matches what addJavascriptInterface() registered.
-keep class com.nin0dev.vendroid.webview.VencordNative { *; }

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
