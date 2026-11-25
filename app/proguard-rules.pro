# Vosk + JNA use reflection / native callbacks
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# WebView bridge
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class com.manikhossain.nova.MainActivity$Bridge { *; }
# services declared in the manifest
-keep class com.manikhossain.nova.NovaAccess { *; }
-keep class com.manikhossain.nova.NotifListener { *; }
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
