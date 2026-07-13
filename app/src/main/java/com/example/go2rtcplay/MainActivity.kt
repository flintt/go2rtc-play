package com.example.go2rtcplay

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.go2rtcplay.data.ConfigRepository
import com.example.go2rtcplay.data.ServerAddress
import com.example.go2rtcplay.network.CameraInfo
import com.example.go2rtcplay.network.Go2RtcClient
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets


class MainActivity : AppCompatActivity() {

    private lateinit var configRepo: ConfigRepository
    private lateinit var webView: WebView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var errorPanel: View
    private lateinit var retryBtn: Button
    private lateinit var serverText: TextView
    private lateinit var settingsBtn: ImageButton
    private lateinit var refreshBtn: ImageButton
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
        errorPanel = findViewById(R.id.errorPanel)
        retryBtn = findViewById(R.id.retryBtn)
        serverText = findViewById(R.id.serverText)
        refreshBtn = findViewById(R.id.refreshBtn)
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
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) showUI()
            false
        }
        webView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                showUI()
                if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                    showConfig()
                    return@setOnKeyListener true
                }
            }
            false
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
                webView.visibility = View.VISIBLE
                webView.requestFocus()
            }
        }
    }

    private fun setupButtons() {
        settingsBtn.setOnClickListener { showConfig() }
        refreshBtn.setOnClickListener { currentServer?.let { connectToServer(it) } }
        retryBtn.setOnClickListener { currentServer?.let { connectToServer(it) } ?: showConfig() }
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
        serverText.text = server.url
        loadingIndicator.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        webView.visibility = View.GONE
        showUI()

        scope.launch {
            val client = Go2RtcClient(server.url)
            val connected = client.checkConnection()

            if (!connected) {
                showError(getString(R.string.connect_error, server.url))
                return@launch
            }

            val onlineCams = client.getStreams().filter { it.online }
            if (onlineCams.isEmpty()) {
                showError(getString(R.string.no_streams))
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
                if (newCams.isNotEmpty()) {
                    android.widget.Toast.makeText(this@MainActivity,
                        getString(R.string.toast_new_cams, newCams.size),
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            val camMap = onlineCams.associateBy { it.name }
            // known cameras first (saved order), new appended
            cameras = (savedNames.filter { it in onlineNames } +
                       onlineNames.filter { it !in savedNames })
                      .distinct().mapNotNull { camMap[it] }

            val refreshSec = (configRepo.getRefreshInterval() / 1000).coerceAtLeast(1)
            serverText.text = getString(R.string.main_status, server.host, cameras.size, refreshSec)
            loadStreamPage()
        }
    }

    private fun showError(message: String) {
        loadingIndicator.visibility = View.GONE
        webView.visibility = View.GONE
        errorPanel.visibility = View.VISIBLE
        errorText.text = message
        showUI()
    }

    private fun loadStreamPage() {
        val server = currentServer ?: return
        val refreshIntervalMs = configRepo.getRefreshInterval()
        val liveLabel = getString(R.string.camera_live)
        val connectingLabel = getString(R.string.camera_connecting)
        val retryingLabel = getString(R.string.camera_retrying)
        val offlineLabel = getString(R.string.camera_offline)

        val html = buildString {
            append("""
<!DOCTYPE html>
<html>
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
*{margin:0;padding:0;box-sizing:border-box}
html,body{height:100%;width:100%;overflow:hidden;background:#090c10;color:#fff;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}
body{touch-action:manipulation}
.grid{display:grid;gap:8px;padding:8px;height:100vh;width:100vw;background:#090c10}
.cam{width:100%;height:100%;overflow:hidden;position:relative;background:#111820;cursor:pointer;outline:none;border:1px solid rgba(148,163,184,.16);border-radius:16px;box-shadow:0 14px 34px rgba(0,0,0,.28),inset 0 0 0 1px rgba(255,255,255,.04)}
.cam:before{content:"";position:absolute;inset:0;background:linear-gradient(180deg,rgba(255,255,255,.05),rgba(255,255,255,0) 28%,rgba(0,0,0,.22));z-index:1;pointer-events:none}
.cam:focus{border-color:#14b8a6;box-shadow:0 0 0 3px rgba(20,184,166,.42),0 16px 36px rgba(0,0,0,.32),inset 0 0 0 1px rgba(255,255,255,.08)}
.cam img{width:100%;height:100%;object-fit:contain;background:#070a0e;transition:opacity .18s ease,transform .18s ease}
.cam.live img{opacity:1}
.cam.offline img,.cam.probing img{opacity:.22}
.cam.probing:after{content:"";position:absolute;left:14px;right:14px;bottom:42px;height:3px;border-radius:999px;background:linear-gradient(90deg,rgba(20,184,166,.1),rgba(20,184,166,.86),rgba(20,184,166,.1));z-index:2;animation:pulse 1.2s ease-in-out infinite}
.lbl{position:absolute;left:10px;bottom:10px;z-index:3;max-width:calc(100% - 20px);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;background:rgba(9,12,16,.78);color:#f8fafc;font-size:13px;line-height:1.25;padding:6px 10px;border:1px solid rgba(255,255,255,.08);border-radius:999px;pointer-events:none}
.state{position:absolute;right:10px;top:10px;z-index:3;background:rgba(9,12,16,.78);color:#cbd5e1;font-size:11px;line-height:1;padding:6px 8px;border:1px solid rgba(255,255,255,.08);border-radius:999px;pointer-events:none}
.cam.live .state{color:#86efac;border-color:rgba(52,211,153,.34)}
.cam.offline .state{color:#fca5a5;border-color:rgba(251,113,133,.34)}
.empty{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;color:#9ca3af;font-size:13px;pointer-events:none}
@keyframes pulse{0%,100%{opacity:.28;transform:scaleX(.72)}50%{opacity:1;transform:scaleX(1)}}
</style>
</head>
<body>
<div class="grid">
""".trimIndent())
            cameras.forEachIndexed { index, cam ->
                val frameUrl = "/api/frame.jpeg?src=${urlEncode(cam.name)}"
                val isKnown = cam.name in knownNames
                val nameAttr = htmlEscape(cam.name)
                val display = if (isKnown) "" else """ style="display:none""""
                val active = if (isKnown) "1" else "0"
                val stateLabel = if (isKnown) connectingLabel else offlineLabel
                if (isKnown) {
                    append("""
<div class="cam probing" tabindex="0" data-name="$nameAttr" data-src="$frameUrl" data-active="$active" data-known="1"$display>
<img id="cam$index" alt="$nameAttr">
<div class="state">$stateLabel</div>
<div class="lbl">$nameAttr</div>
</div>
""".trimIndent())
                } else {
                    append("""
<div class="cam probing" tabindex="0" data-name="$nameAttr" data-src="$frameUrl" data-active="$active" data-known="0"$display>
<img id="cam$index" alt="$nameAttr">
<div class="state">$stateLabel</div>
<div class="lbl">$nameAttr</div>
</div>
""".trimIndent())
                }
            }
            append("""
</div>
<script>
var fc={},cc={},currentCols=1;
var LABEL_LIVE=${jsString(liveLabel)},LABEL_CONNECTING=${jsString(connectingLabel)},LABEL_RETRYING=${jsString(retryingLabel)},LABEL_OFFLINE=${jsString(offlineLabel)};
function visibleCells(){
    var all=document.querySelectorAll('.cam'),out=[];
    for(var i=0;i<all.length;i++){if(all[i].style.display!=='none')out.push(all[i]);}
    return out;
}
function setState(cell,cls,label){
    cell.classList.remove('live','offline','probing');
    if(cls)cell.classList.add(cls);
    var s=cell.querySelector('.state');
    if(s)s.textContent=label||'';
}
function relayout(){
    var v=visibleCells().length;
    if(v===0)return;
    var land=window.innerWidth>=window.innerHeight;
    var cols=land?Math.max(1,Math.ceil(Math.sqrt(v))):Math.max(1,Math.floor(Math.sqrt(v)));
    var rows=Math.ceil(v/cols);
    currentCols=cols;
    var g=document.querySelector('.grid');
    if(g){
        g.style.gridTemplateColumns='repeat('+cols+',1fr)';
        g.style.gridTemplateRows='repeat('+rows+',1fr)';
    }
}
function showCell(cell){
    if(cell.style.display==='none'){
        cell.style.display='';
        cell.setAttribute('data-active','1');
        relayout();
    }
}
function hideCell(cell){
    cell.style.display='none';
    cell.setAttribute('data-active','0');
    relayout();
}
function openCell(cell){
    var n=cell&&cell.getAttribute('data-name');
    if(n){try{Android.onCameraClick(n);}catch(e){}}
}
function focusMove(cell,key){
    var cells=visibleCells();
    if(!cells.length)return;
    var i=cells.indexOf(cell);
    if(i<0)i=0;
    var next=i;
    if(key==='ArrowRight')next=i+1;
    if(key==='ArrowLeft')next=i-1;
    if(key==='ArrowDown')next=i+currentCols;
    if(key==='ArrowUp')next=i-currentCols;
    next=Math.max(0,Math.min(cells.length-1,next));
    if(cells[next])cells[next].focus();
}
function bindCell(cell){
    cell.addEventListener('click',function(){openCell(cell);});
    cell.addEventListener('keydown',function(ev){
        if(ev.key==='Enter'||ev.key===' '){ev.preventDefault();openCell(cell);return;}
        if(ev.key==='ArrowRight'||ev.key==='ArrowLeft'||ev.key==='ArrowDown'||ev.key==='ArrowUp'){
            ev.preventDefault();focusMove(cell,ev.key);
        }
    });
}
function refreshFrames(){
    var t=Date.now(),cells=document.querySelectorAll('.cam[data-active="1"]');
    for(var i=0;i<cells.length;i++){
        (function(cell,idx){
            setTimeout(function(){
                var e=cell.querySelector('img');
                var src=cell.getAttribute('data-src');
                if(!e||!src)return;
                var url=src+'&_='+t+'_'+idx;
                var img=new Image();
                img.onload=function(){
                    var n=cell.getAttribute('data-name')||'';
                    fc[n]=0;
                    if(!cc[n]){cc[n]=1;try{Android.onCameraWorking(n);}catch(e){}}
                    showCell(cell);
                    setState(cell,'live',LABEL_LIVE);
                    e.src=url;
                };
                img.onerror=function(){
                    var n=cell.getAttribute('data-name')||e.id||'x';
                    fc[n]=(fc[n]||0)+1;
                    if(fc[n]>=3){
                        if(cell.getAttribute('data-known')==='1'){
                            setState(cell,'offline',LABEL_RETRYING);
                        }else{
                            hideCell(cell);
                            try{Android.onCameraFailed(n);}catch(e){}
                        }
                    }else{
                        setState(cell,'probing',LABEL_CONNECTING);
                    }
                };
                img.src=url;
            },idx*300);
        })(cells[i],i);
    }
}
function recheckHidden(){
    var t=Date.now(),cams=document.querySelectorAll('.cam'),idx=0;
    for(var i=0;i<cams.length;i++){
        var cell=cams[i];
        if(cell.style.display==='none'){
            var img=cell.querySelector('img');
            var src=cell.getAttribute('data-src');
            if(!img||!src)continue;
            var nm=cell.getAttribute('data-name');
            if(!nm)continue;
            (function(im,nm2,src2,cell2,ii){
                setTimeout(function(){
                    var url=src2+'&_='+t+'_r'+ii;
                    var test=new Image();
                    test.onload=function(){
                        if(!cc[nm2]){cc[nm2]=1;try{Android.onCameraWorking(nm2);}catch(e){}}
                        im.src=url;
                        cell2.setAttribute('data-active','1');
                        showCell(cell2);
                        setState(cell2,'live',LABEL_LIVE);
                    };
                    test.src=url;
                },ii*500);
            })(img,nm,src,cell,idx);
            idx++;
        }
    }
}
var cells=document.querySelectorAll('.cam');
for(var bi=0;bi<cells.length;bi++)bindCell(cells[bi]);
relayout();
refreshFrames();
recheckHidden();
setInterval(function(){refreshFrames();},${refreshIntervalMs});
setInterval(function(){recheckHidden();},30000);
window.addEventListener('resize',relayout);
setTimeout(function(){var v=visibleCells();if(v[0])v[0].focus();},200);
</script>
</body>
</html>
""".trimIndent())
        }

        streamUrl = "${server.url}/stream.html?"
        webView.loadDataWithBaseURL("${server.url}/", html, "text/html", "UTF-8", null)
    }

    private fun openFullscreen(cameraName: String) {
        val server = currentServer ?: return
        val protocol = configRepo.getPreferredProtocol()
        val modeParam = if (protocol.isNotEmpty()) "&mode=$protocol" else ""
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
            topBar.visibility = if (uiVisible) View.VISIBLE else View.GONE
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

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun jsString(value: String): String =
        JSONObject.quote(value)

    private fun htmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    override fun onDestroy() {
        scope.cancel()
        autoHideHandler.removeCallbacks(autoHideRunnable)
        super.onDestroy()
    }
}
