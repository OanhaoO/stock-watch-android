package com.stockwatch.app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import com.stockwatch.app.panel.PanelApi
import com.stockwatch.app.panel.PanelServer

class PanelActivity : Activity() {
    private var server: PanelServer? = null
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "盯盘配置面板"
        val panelServer = PanelServer(this, PanelApi(this))
        try {
            panelServer.start()
            server = panelServer
        } catch (exc: Exception) {
            setContentView(
                TextView(this).apply {
                    text = "本地面板启动失败：${exc.message ?: "端口可能被占用"}"
                    textSize = 16f
                    setPadding(48, 48, 48, 48)
                },
            )
            return
        }
        val view = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val allowed = uri.scheme == "http" &&
                        (uri.host == PanelServer.LOOPBACK || uri.host == "localhost") &&
                        uri.port == PanelServer.DEFAULT_PORT
                    return !allowed
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    val uri = android.net.Uri.parse(url)
                    val allowed = uri.scheme == "http" &&
                        (uri.host == PanelServer.LOOPBACK || uri.host == "localhost") &&
                        uri.port == PanelServer.DEFAULT_PORT
                    return !allowed
                }
            }
            loadUrl("http://${PanelServer.LOOPBACK}:${PanelServer.DEFAULT_PORT}/#token=${panelServer.accessToken}")
        }
        webView = view
        setContentView(view)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        server?.close()
        server = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView?.canGoBack() == true) webView?.goBack() else super.onBackPressed()
    }
}
