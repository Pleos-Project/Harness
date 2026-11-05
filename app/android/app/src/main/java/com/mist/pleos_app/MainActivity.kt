package com.mist.pleos_app

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader

/**
 * 빌드 환경 확인용 빈 화면. assets/index.html 을 WebView로 띄운다.
 *
 * 서빙 방식(H_Mate에서 가져옴): file:// 대신 WebViewAssetLoader로
 * https://appassets.androidplatform.net/assets/ 에서 연다(ES 모듈·fetch가 동작하도록).
 * 이 origin은 https라 http 서버 호출은 mixed content로 막힌다 → app/README.md 3절.
 * 페이지 console 로그는 logcat 태그 "ChagokWeb".
 */
class MainActivity : Activity() {

    private lateinit var web: WebView

    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // 로컬 장부·설정(localStorage)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assetLoader.shouldInterceptRequest(request.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    Log.println(
                        if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.ERROR else Log.INFO,
                        "ChagokWeb", "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})",
                    )
                    return true
                }
            }
            loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
        setContentView(web)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
