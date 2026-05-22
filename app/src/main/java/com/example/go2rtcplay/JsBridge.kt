package com.example.go2rtcplay

import android.webkit.JavascriptInterface

class JsBridge(private val onFullscreenRequest: (String) -> Unit) {

    @JavascriptInterface
    fun onFullscreen(cameraName: String) {
        onFullscreenRequest(cameraName)
    }

    @JavascriptInterface
    fun onCameraClick(cameraName: String) {
        onFullscreenRequest(cameraName)
    }
}
