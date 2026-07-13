package com.example.go2rtcplay

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class FullscreenActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var backBtn: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fullscreen)

        webView = findViewById(R.id.fullscreenWebView)
        backBtn = findViewById(R.id.backBtn)

        val serverUrl = intent.getStringExtra("server_url") ?: ""
        val cameraName = intent.getStringExtra("camera_name") ?: ""
        val modeParam = intent.getStringExtra("mode_param") ?: ""
        val url = "$serverUrl/stream.html?src=${urlEncode(cameraName)}$modeParam"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectHideUI(view)
            }
        }
        webView.loadUrl(url)

        backBtn.setOnClickListener { finish() }
        applyImmersiveForOrientation()
    }

    private fun injectHideUI(view: WebView?) {
        val js = """
            (function() {
                var s = document.createElement('style');
                s.id = '__hideui';
                s.textContent = 'video::-webkit-media-controls{display:none!important}video::-webkit-media-controls-enclosure{display:none!important}video::-webkit-media-controls-panel{display:none!important}';
                document.head.appendChild(s);
                var v = document.querySelector('video');
                if (v) { v.controls = false; v.removeAttribute('controls'); }
            })();
        """.trimIndent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            view?.evaluateJavascript(js, null)
        } else {
            view?.loadUrl("javascript:$js")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersiveForOrientation()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveForOrientation()
    }

    private fun applyImmersiveForOrientation() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            backBtn.visibility = View.GONE
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        } else {
            backBtn.visibility = View.VISIBLE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
