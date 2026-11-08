# The xterm.js page calls these through the WebView JavaScript bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# OkHttp / Okio optional platform classes
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# librexterm's JNI entry points are bound by class and method name.
-keep class com.wiselabs.rhubarb.ghostty.GhosttyBridge { native <methods>; *; }
