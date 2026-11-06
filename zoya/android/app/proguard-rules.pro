# Keep OkHttp's platform bits when minifying.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# kotlinx.serialization models (DTOs for the Live protocol).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class ai.zoya.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class ai.zoya.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
