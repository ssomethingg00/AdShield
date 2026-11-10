# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# AndroidX Room
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# AdShield VPN components (never obfuscate)
-keep class com.example.adshield.vpn.** { *; }
-keep class com.example.adshield.blocklist.** { *; }

# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
