# Keep the ML Kit entry points that are resolved reflectively by the bundled models.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Room's generated implementations
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Kotlin serialization is used for nothing user-facing yet, but keep the generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
