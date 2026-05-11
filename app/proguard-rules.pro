# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the Android SDK.

# Keep JSBridge methods
-keepclassmembers class com.soul2.imageai.webview.** {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView related
-keep class android.webkit.** { *; }
