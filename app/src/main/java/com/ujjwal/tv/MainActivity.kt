package com.ujjwal.tv

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var rootLayout: FrameLayout
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private var remoteServer: ServerSocket? = null
    private val remotePort = 8765
    private val pin: String by lazy { (1000..9999).random().toString() }
    @Volatile private var pairedToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        rootLayout = FrameLayout(this)
        webView = WebView(this)
        playerView = PlayerView(this).apply {
            visibility = View.GONE
            useController = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        rootLayout.addView(playerView)
        setContentView(rootLayout)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowUniversalAccessFromFileURLs = true
        }
        webView.addJavascriptInterface(RemoteBridge(), "HDTVRemote")
        webView.addJavascriptInterface(NativePlayerBridge(), "HDTVPlayer")
        webView.webViewClient = WebViewClient()
        webView.loadUrl("file:///android_asset/index.html")
        webView.requestFocus()
        startRemoteServer()
    }

    inner class NativePlayerBridge {
        @JavascriptInterface
        fun play(url: String, title: String) {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return
            runOnUiThread {
                try {
                    val player = exoPlayer ?: ExoPlayer.Builder(this@MainActivity).build().also {
                        exoPlayer = it
                        playerView.player = it
                    }
                    val item = MediaItem.Builder()
                        .setUri(url)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                        .build()
                    webView.visibility = View.GONE
                    playerView.visibility = View.VISIBLE
                    playerView.requestFocus()
                    player.setMediaItem(item)
                    player.prepare()
                    player.playWhenReady = true
                } catch (_: Exception) {
                    playerView.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
            }
        }
    }

    inner class RemoteBridge {
        @JavascriptInterface fun details(): String {
            val ip = localIpAddress() ?: "TV का IP उपलब्ध नहीं"
            return "$ip:$remotePort|$pin"
        }
    }

    private fun localIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                val addresses = network.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress?.contains(':') == false) {
                        return address.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun startRemoteServer() {
        thread(name = "hd-tv-remote-server", isDaemon = true) {
            try {
                remoteServer = ServerSocket(remotePort)
                while (!remoteServer!!.isClosed) {
                    val socket = remoteServer!!.accept()
                    thread(isDaemon = true) { handleRemoteRequest(socket) }
                }
            } catch (_: Exception) { }
        }
    }

    private fun handleRemoteRequest(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 4000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val target = parts.getOrNull(1) ?: "/"
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val split = line.indexOf(':')
                    if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                }
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                val body = CharArray(length.coerceIn(0, 8192))
                var read = 0
                while (read < body.size) {
                    val n = reader.read(body, read, body.size - read)
                    if (n <= 0) break
                    read += n
                }
                val path = target.substringBefore("?")
                val query = target.substringAfter("?", "")
                val params = query.split("&").mapNotNull {
                    val p = it.split("=", limit = 2)
                    if (p.size == 2) p[0] to java.net.URLDecoder.decode(p[1], "UTF-8") else null
                }.toMap()
                when {
                    method == "GET" && path == "/remote" -> respond(client.getOutputStream(), 200, "text/html; charset=utf-8", remotePage())
                    method == "POST" && path == "/pair" -> {
                        if (params["pin"] == pin) {
                            pairedToken = UUID.randomUUID().toString()
                            respond(client.getOutputStream(), 200, "text/plain", pairedToken!!)
                        } else respond(client.getOutputStream(), 403, "text/plain", "गलत PIN")
                    }
                    method == "POST" && path == "/key" -> {
                        if (params["token"] != null && params["token"] == pairedToken) {
                            val key = when (params["key"]) {
                                "UP" -> KeyEvent.KEYCODE_DPAD_UP
                                "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
                                "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
                                "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
                                "OK" -> KeyEvent.KEYCODE_DPAD_CENTER
                                "BACK" -> KeyEvent.KEYCODE_BACK
                                "PLAY" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                "VOLUP" -> KeyEvent.KEYCODE_VOLUME_UP
                                "VOLDOWN" -> KeyEvent.KEYCODE_VOLUME_DOWN
                                "MUTE" -> KeyEvent.KEYCODE_VOLUME_MUTE
                                else -> -1
                            }
                            if (key >= 0) runOnUiThread {
                                if (key == KeyEvent.KEYCODE_BACK && playerView.visibility == View.VISIBLE) {
                                    onBackPressed()
                                } else {
                                    val target = if (playerView.visibility == View.VISIBLE) playerView else webView
                                    val now = android.os.SystemClock.uptimeMillis()
                                    target.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0))
                                    target.dispatchKeyEvent(KeyEvent(now, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, key, 0))
                                }
                            }
                            respond(client.getOutputStream(), 200, "text/plain", "OK")
                        } else respond(client.getOutputStream(), 403, "text/plain", "पहले PIN से pair करें")
                    }
                    else -> respond(client.getOutputStream(), 404, "text/plain", "Not found")
                }
            } catch (_: Exception) { }
        }
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val label = if (status == 200) "OK" else if (status == 403) "Forbidden" else "Not Found"
        out.write("HTTP/1.1 $status $label\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun remotePage() = """<!doctype html><html lang="hi"><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><title>HD-TV-App Remote</title>
<style>body{margin:0;background:#08111f;color:#fff;font-family:Arial;text-align:center;padding:22px}h1{color:#38bdf8}input,button{font-size:18px;padding:14px;border:0;border-radius:12px;margin:6px}input{width:120px;text-align:center;letter-spacing:5px}button{background:#12365a;color:white;min-width:68px;min-height:58px}button:active{background:#0284c7}.pad{display:grid;grid-template-columns:repeat(3,1fr);max-width:300px;margin:22px auto;gap:8px}.wide{width:90%;max-width:300px}.hint{color:#9fb2c8}</style>
<body><h1>📺 HD-TV-App</h1><p>TV और मोबाइल एक ही Wi-Fi पर रखें</p><div id="login"><p>TV स्क्रीन पर दिख रहा 4-digit PIN डालें</p><input id="pin" inputmode="numeric" maxlength="4" placeholder="PIN"><br><button class="wide" onclick="pair()">Connect Remote</button></div><div id="remote" hidden><p id="status">Connected ✓</p><div class="pad"><span></span><button onclick="send('UP')">▲</button><span></span><button onclick="send('LEFT')">◀</button><button onclick="send('OK')">OK</button><button onclick="send('RIGHT')">▶</button><span></span><button onclick="send('DOWN')">▼</button><span></span></div><button onclick="send('BACK')">↩ Back</button><button onclick="send('PLAY')">⏯ Play/Pause</button><br><button onclick="send('VOLUP')">Vol +</button><button onclick="send('VOLDOWN')">Vol −</button><button onclick="send('MUTE')">Mute</button><p class="hint">Remote connected to your TV over local Wi-Fi.</p></div><p id="msg" class="hint"></p><script>let token='';async function pair(){let p=document.getElementById('pin').value;try{let r=await fetch('/pair?pin='+encodeURIComponent(p),{method:'POST'});let t=await r.text();if(!r.ok)throw Error(t);token=t;document.getElementById('login').hidden=true;document.getElementById('remote').hidden=false;}catch(e){document.getElementById('msg').textContent=e.message||'Connection failed';}}async function send(k){try{await fetch('/key?token='+encodeURIComponent(token)+'&key='+k,{method:'POST'});}catch(e){document.getElementById('status').textContent='Connection lost';}}</script></body></html>"""

    override fun onDestroy() {
        try { remoteServer?.close() } catch (_: Exception) {}
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (::playerView.isInitialized && playerView.visibility == View.VISIBLE) {
            exoPlayer?.pause()
            playerView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.evaluateJavascript("closePlayer()", null)
            webView.requestFocus()
        } else if (webView.canGoBack()) {
            webView.goBack()
        } else {
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        }
    }
}
