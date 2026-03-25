# Bouncy Castle: keep all classes and prevent R8 from generating invalid DEX
# R8 horizontal/vertical class merging causes VerifyError in BC static initializers
-keep class org.bouncycastle.** { *; }
-keepclassmembers class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keepattributes InnerClasses,EnclosingMethod

# Disable R8 optimizations known to break BouncyCastle
-optimizations !class/merging/horizontal,!class/merging/vertical,!code/simplification/advanced

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# RavenTag data classes
-keep class io.raventag.app.ravencoin.** { *; }

# RavenTag wallet and crypto: keep class names for Android Keystore alias lookup
-keep class io.raventag.app.wallet.** { *; }
-keep class io.raventag.app.nfc.SunVerifier { *; }

# Tink / security-crypto (EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Retrofit
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
