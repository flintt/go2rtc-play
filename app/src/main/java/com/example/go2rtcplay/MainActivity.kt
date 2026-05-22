package com.example.go2rtcplay

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.go2rtcplay.data.ConfigRepository
import com.example.go2rtcplay.data.ServerAddress
import com.example.go2rtcplay.network.CameraInfo
import com.example.go2rtcplay.network.Go2RtcClient
import kotlinx.coroutines.*


class MainActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var serverText: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var topBar: View

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentServer: ServerAddress? = null
    private var cameras: List<CameraInfo> = emptyList()
    private var streamUrl: String? = null
    private var backPressedTime = 0L
    private var uiVisible = true
    private val autoHideHandler = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable { hideUI() }

    private var knownNames: Set<String> = emptySet()

    companion object {
        private const val REQUEST_CONFIG = 1
        private const val AUTO_HIDE_DELAY_MS = 3000L
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configRepo = ConfigRepository(this)

        settingsBtn = findViewById(R.id.settingsBtn)
        webView = findViewById(R.id.webView)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        errorText = findViewById(R.id.errorText)
        serverText = findViewById(R.id.serverText)
        topBar = findViewById(R.id.topBar)

        setupWebView()
        setupButtons()

        val server = configRepo.getActiveServer()
        if (server != null) {
            connectToServer(server)
        } else {
            showConfig()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onCameraClick(name: String) {
                    runOnUiThread { openFullscreen(name) }
                }
                @JavascriptInterface
                fun onCameraWorking(name: String) {
                    runOnUiThread {
                        val names = configRepo.getCameraNames().toMutableList()
                        if (name !in names) {
                            names.add(name)
                            configRepo.saveCameraNames(names)
                        }
                    }
                }
                @JavascriptInterface
                fun onCameraFailed(name: String) {
                    runOnUiThread {
                        val names = configRepo.getCameraNames().toMutableList()
                        if (name in names) {
                            names.remove(name)
                            configRepo.saveCameraNames(names)
                        }
                    }
                }
            },
            "Android"
        )

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url == "about:blank") return
                loadingIndicator.visibility = View.GONE
            }
        }
    }

    private fun setupButtons() {
        settingsBtn.setOnClickListener { showConfig() }
    }

    private fun showUI() {
        uiVisible = true
        topBar.visibility = View.VISIBLE
        resetAutoHideTimer()
    }

    private fun hideUI() {
        uiVisible = false
        topBar.visibility = View.GONE
        autoHideHandler.removeCallbacks(autoHideRunnable)
    }

    private fun resetAutoHideTimer() {
        autoHideHandler.removeCallbacks(autoHideRunnable)
        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun connectToServer(server: ServerAddress) {
        currentServer = server
        serverText.text = server.host
        loadingIndicator.visibility = View.VISIBLE
        errorText.visibility = View.GONE
        showUI()

        scope.launch {
            val client = Go2RtcClient(server.url)
            val connected = client.checkConnection()

            if (!connected) {
                loadingIndicator.visibility = View.GONE
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.connect_error, server.url)
                return@launch
            }

            val onlineCams = client.getStreams().filter { it.online }
            if (onlineCams.isEmpty()) {
                loadingIndicator.visibility = View.GONE
                errorText.visibility = View.VISIBLE
                errorText.text = getString(R.string.no_streams)
                return@launch
            }

            val savedNames = configRepo.getCameraNames()
            val onlineNames = onlineCams.map { it.name }.toSet()

            // first launch: treat all as known so grid shows immediately
            if (savedNames.isEmpty()) {
                knownNames = onlineNames
                android.widget.Toast.makeText(this@MainActivity,
                    getString(R.string.toast_first_launch, onlineNames.size), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                knownNames = savedNames.filter { it in onlineNames }.toSet()
                val newCams = onlineNames - knownNames
                android.widget.Toast.makeText(this@MainActivity,
                    getString(R.string.toast_known_cams, knownNames.size, newCams.size, newCams.joinToString(",")),
                    android.widget.Toast.LENGTH_SHORT).show()
            }

            val camMap = onlineCams.associateBy { it.name }
            // known cameras first (saved order), new appended
            cameras = (savedNames.filter { it in onlineNames } +
                       onlineNames.filter { it !in savedNames })
                      .distinct().mapNotNull { camMap[it] }

            loadStreamPage()
        }
    }

    private fun loadStreamPage() {
        val server = currentServer ?: return

        val html = buildString {
            append("""
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{margin:0;padding:0;overflow:hidden;background:#000;height:100vh;width:100vw}
.grid{display:grid;gap:2px;height:100vh;width:100vw}
.cam{width:100%;height:100%;overflow:hidden;position:relative;background:#111;cursor:pointer;outline:none;border:0}
.cam:focus{outline:2px solid #4CAF50;outline-offset:-2px}
.cam img{width:100%;height:100%;object-fit:contain}
.cam .lbl{position:absolute;bottom:2px;left:2px;background:rgba(0,0,0,0.6);color:#0f0;font-size:11px;padding:1px 4px;font-family:sans-serif;pointer-events:none}
</style>
</head>
<body>
<div class="grid">
""".trimIndent())
            cameras.forEach { cam ->
                val frameUrl = "${server.url}/api/frame.jpeg?src=${cam.name}"
                val isKnown = cam.name in knownNames
                if (isKnown) {
                    append("""
<div class="cam" tabindex="0" data-name="${cam.name}" onclick="Android.onCameraClick('${cam.name}')" onkeydown="if(event.key==='Enter')Android.onCameraClick('${cam.name}')">
<img id="i${cam.name}" data-src="$frameUrl" alt="${cam.name}">
<div class="lbl">${cam.name}</div>
</div>
""".trimIndent())
                } else {
                    append("""
<div class="cam" tabindex="0" data-name="${cam.name}" style="display:none" onclick="Android.onCameraClick('${cam.name}')" onkeydown="if(event.key==='Enter')Android.onCameraClick('${cam.name}')">
<img id="i${cam.name}" alt="${cam.name}">
<div class="lbl">${cam.name}</div>
</div>
""".trimIndent())
                }
            }
            append("""
</div>
<script>
var fc={},cc={};
function relayout(){
    var v=0,cams=document.querySelectorAll('.cam');
    for(var i=0;i<cams.length;i++){if(cams[i].style.display!=='none')v++;}
    if(v===0)return;
    var land=window.innerWidth>=window.innerHeight;
    var cols=land?Math.max(1,Math.ceil(Math.sqrt(v))):Math.max(1,Math.round(Math.sqrt(v)*0.7));
    var rows=Math.ceil(v/cols);
    var g=document.querySelector('.grid');
    if(g){
        g.style.gridTemplateColumns='repeat('+cols+',1fr)';
        g.style.gridTemplateRows='repeat('+rows+',1fr)';
    }
}
function refreshFrames(){
    var t=Date.now(),els=document.querySelectorAll('[data-src]');
    for(var i=0;i<els.length;i++){
        (function(e,idx){
            setTimeout(function(){
                var url=e.getAttribute('data-src')+'&_='+t+'_'+idx;
                var img=new Image();
                img.onload=function(){
                    var cell=e.parentElement;
                    var n=cell.getAttribute('data-name')||'';
                    fc[n]=0;
                    if(!cc[n]){cc[n]=1;try{Android.onCameraWorking(n);}catch(e){}}
                    if(cell&&cell.style.display==='none'){
                        cell.style.display='';
                        relayout();
                    }
                    e.src=url;
                };
                img.onerror=function(){
                    var cell=e.parentElement;
                    var n=cell.getAttribute('data-name')||e.id||'x';
                    fc[n]=(fc[n]||0)+1;
                    if(fc[n]>=3){
                        if(cell){cell.style.display='none';try{Android.onCameraFailed(n);}catch(e){}relayout();}
                    }
                };
                img.src=url;
            },idx*300);
        })(els[i],i);
    }
}
function recheckHidden(){
    var t=Date.now(),cams=document.querySelectorAll('.cam'),idx=0;
    for(var i=0;i<cams.length;i++){
        var cell=cams[i];
        if(cell.style.display==='none'){
            var img=cell.querySelector('img');
            if(!img||img.getAttribute('data-src'))continue;
            var nm=cell.getAttribute('data-name');
            if(!nm)continue;
            (function(im,nm2,ii){
                setTimeout(function(){
                    var url='/api/frame.jpeg?src='+nm2+'&_='+t+'_r'+ii;
                    var test=new Image();
                    test.onload=function(){
                        im.setAttribute('data-src','/api/frame.jpeg?src='+nm2);
                        if(!cc[nm2]){cc[nm2]=1;try{Android.onCameraWorking(nm2);}catch(e){}}
                        im.parentElement.style.display='';
                        relayout();
                    };
                    test.src=url;
                },ii*500);
            })(img,nm,idx);
            idx++;
        }
    }
}
relayout();
refreshFrames();
recheckHidden();
setInterval(function(){refreshFrames();},5000);
setInterval(function(){recheckHidden();},30000);
window.addEventListener('resize',relayout);
</script>
</body>
</html>
""".trimIndent())
        }

        streamUrl = "${server.url}/stream.html?"
        webView.loadDataWithBaseURL(server.url, html, "text/html", "UTF-8", null)
    }

    private fun openFullscreen(cameraName: String) {
        val server = currentServer ?: return
        val protocol = configRepo.getPreferredProtocol()
        val modeParam = if (protocol.isNotEmpty()) "&mode=$protocol" else ""
        val url = "${server.url}/stream.html?src=$cameraName$modeParam"
        android.widget.Toast.makeText(this, url, android.widget.Toast.LENGTH_LONG).show()
        val intent = Intent(this, FullscreenActivity::class.java).apply {
            putExtra("server_url", server.url)
            putExtra("camera_name", cameraName)
            putExtra("mode_param", modeParam)
        }
        startActivity(intent)
    }

    private fun showConfig() {
        val intent = Intent(this, ConfigActivity::class.java)
        startActivityForResult(intent, REQUEST_CONFIG)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIG && resultCode == RESULT_OK) {
            recreate()
        }
    }

    override fun onBackPressed() {
        if (backPressedTime + 2000 > System.currentTimeMillis()) {
            finishAffinity()
        } else {
            backPressedTime = System.currentTimeMillis()
            showConfig()
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveForOrientation()
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
            topBar.visibility = View.GONE
            uiVisible = false
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    override fun onDestroy() {
        scope.cancel()
        autoHideHandler.removeCallbacks(autoHideRunnable)
        super.onDestroy()
    }
}
