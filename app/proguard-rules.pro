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

# Keep line numbers + source file so release crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# ===================== App models used via reflection / serialization =====================
# Room database entities.
-keep class com.irozar.ipdfmaster.data.entity.** { *; }
# Moshi @JsonClass DTOs + their KSP-generated *JsonAdapter classes (Gemini API).
-keep class com.irozar.ipdfmaster.api.** { *; }

# ===================== PDFBox (tom_roush) - loads fonts/resources by name ==================
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**

# ===================== Moshi =====================
-keep class com.squareup.moshi.** { *; }
-keep,allowobfuscation,allowshrinking @com.squareup.moshi.JsonClass class *
-keep class **JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson <methods>;
    @com.squareup.moshi.ToJson <methods>;
}
# Kotlin reflection metadata used by Moshi's Kotlin support.
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }

# ===================== Retrofit / OkHttp / Okio (ship own rules; silence warnings) =========
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn javax.annotation.**

# Google AI SDK (generativeai) references Ktor, which isn't on the classpath (the app uses
# its own OkHttp path for AI). These references are safe to ignore.
-dontwarn io.ktor.**

# ===================== Enums (valueOf/values via reflection) ==============================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
