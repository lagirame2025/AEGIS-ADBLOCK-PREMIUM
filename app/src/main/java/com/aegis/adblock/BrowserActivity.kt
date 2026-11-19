package com.aegis.adblock

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import android.content.Intent
import android.net.Uri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/** Pola URL iklan yang dicek selain daftar domain (untuk YouTube & situs sejenis). */
object UrlRules {
    private val parts = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info", "/youtubei/v1/player/ad_break",
        "&adformat=", "ad_break", "doubleclick.net", "googleads", "/ads/ga-audiences", "adservice.google"
    )
    fun match(url: String) = parts.any { url.contains(it) }
}

class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: EditText
    private var script = ""
    private var docStart = false

    private val ytSelectors = listOf(
        ".ytp-ad-overlay-container", ".ytp-ad-module", ".video-ads", "ytd-ad-slot-renderer",
        "ytd-promoted-sparkles-web-renderer", "ytd-display-ad-renderer", "ytd-banner-promo-renderer",
        "#masthead-ad", "ytm-promoted-sparkles-web-renderer", "ytm-companion-ad-renderer",
        "ytd-in-feed-ad-layout-renderer", "ytd-player-legacy-desktop-watch-ads-renderer",
        "ytm-promoted-video-renderer", ".ad-container", ".ad-div"
    )

    private fun buildScript(): String {
        val f = File(filesDir, "cosmetic.txt")
        val extra = (if (f.exists() && f.length() > 0) f.readLines()
                     else assets.open("cosmetic_seed.txt").bufferedReader().readLines())
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(8000)
        val css = (ytSelectors + extra).joinToString("\n") { "$it{display:none!important}" }
        val scriptlets = assets.open("scriptlets.js").bufferedReader().readText()
        val style = "(function(){var st=document.createElement('style');st.textContent=" + JSONObject.quote(css) +
            ";function a(){var p=document.head||document.documentElement;if(p)p.appendChild(st);else setTimeout(a,0);}a();})();"
        return scriptlets + "\n" + style
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Blocklist.load(this)
        script = buildScript()
        bar = EditText(this).apply {
            hint = "Alamat atau kata kunci"; setSingleLine(); imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> go(text.toString()); true }
        }
        val goBtn = Button(this).apply { text = "Buka"; setOnClickListener { go(bar.text.toString()) } }
        val extBtn = Button(this).apply { text = "↗"; setOnClickListener { web.url?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(8, 24, 8, 8)
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(goBtn); addView(extBtn)
        }
        web = WebView(this)
        docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (docStart) WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        web.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (r.isForMainFrame) return null
                val u = r.url
                return if (Blocklist.isBlocked(u.host ?: "") || UrlRules.match(u.toString()))
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) { if (!docStart) v.evaluateJavascript(script, null); bar.setText(url) }
            override fun onPageFinished(v: WebView, url: String) { if (!docStart) v.evaluateJavascript(script, null) }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row); addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        go("https://m.youtube.com")
    }

    private fun go(q: String) {
        val t = q.trim(); if (t.isEmpty()) return
        web.loadUrl(when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        })
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
