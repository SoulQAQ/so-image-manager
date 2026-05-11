package com.soul2.imageai.webview

import android.content.Context
import android.webkit.JavascriptInterface
import org.json.JSONObject

class JsBridge(private val context: Context) {

    @JavascriptInterface
    fun ping(message: String): String {
        val result = JSONObject().apply {
            put("code", 0)
            put("message", "Pong: $message")
            put("data", JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("received", message)
            })
        }
        return result.toString()
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val result = JSONObject().apply {
            put("code", 0)
            put("message", "ok")
            put("data", JSONObject().apply {
                put("appName", "ImageAI")
                put("versionName", "1.0.0")
                put("versionCode", 1)
                put("deviceId", android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ))
            })
        }
        return result.toString()
    }
}
