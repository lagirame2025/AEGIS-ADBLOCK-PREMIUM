package com.aegis.adblock

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.util.Base64
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import android.content.Intent
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Pola URL iklan yang dicek selain daftar domain (untuk YouTube & situs sejenis). */
object UrlRules {
    /** Pola khas iklan: aman dipakai untuk request apa pun (first-party maupun third-party). */
    private val always = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_info", "/youtubei/v1/player/ad_break",
        "&adformat=", "ad_break", "doubleclick.net", "googleads", "/ads/ga-audiences", "adservice.google",
        "googlesyndication", "/pcs/activeview", "/pagead2", "amazon-adsystem.com", "adsbygoogle.js"
    )

    /** Pola path iklan umum: hanya diterapkan pada request LINTAS-SITUS agar konten situs sendiri aman. */
    private val thirdPartyPath = Regex(
        "/(ads?|adserver|adservice|adsystem|adframe|adview|adtag|adunit|adsbygoogle|adverts?|advertising|popunder|popads?|" +
        "prebid[\\w.-]*|pubads[\\w.-]*|gpt\\.js|show_ads[\\w.]*|banner[_-]?ads?)(?=[/._?-]|$)",
        RegexOption.IGNORE_CASE
    )

    private val SLD = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "go", "web", "sch", "my", "biz", "ne", "mil")

    /** Domain "situs" kasar (example.co.id, example.com) untuk membedakan first-party dan third-party. */
    fun site(host: String): String {
        val l = host.lowercase().trimEnd('.').split('.')
        val n = l.size
        if (n <= 2) return host.lowercase()
        return if (l[n - 1].length == 2 && l[n - 2] in SLD) l.takeLast(3).joinToString(".")
               else l.takeLast(2).joinToString(".")
    }

    fun match(url: String) = always.any { url.contains(it) }
    fun isThirdParty(pageHost: String, reqHost: String) = pageHost.isNotEmpty() && site(pageHost) != site(reqHost)
    fun matchThirdPartyPath(path: String) = thirdPartyPath.containsMatchIn(path)
}

class BrowserActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bar: EditText
    private var script = ""
    private var docStart = false
    @Volatile private var pageHost = ""

    private val pixel: ByteArray by lazy {
        Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
    }
    private val extSchemes = setOf("mailto", "tel", "sms", "whatsapp", "tg")
    private val passSchemes = setOf("about", "blob", "data", "file", "javascript")

    private fun buildScript(): String {
        Cosmetic.load(this)
        val scriptlets = assets.open("scriptlets.js").bufferedReader().use { it.readText() }
        return scriptlets + "\nwindow.__aegisCss&&window.__aegisCss('gen'," + JSONObject.quote(Cosmetic.genericCss()) + ");"
    }

    /** Request iklan? Daftar domain + heuristik, pola URL, dan pola path iklan untuk request lintas-situs. */
    private fun isAdRequest(u: Uri, checkPath: Boolean): Boolean {
        val host = u.host ?: return false
        if (Blocklist.isBlocked(host) || UrlRules.match(u.toString())) return true
        val ph = pageHost
        return checkPath && ph.isNotEmpty() && UrlRules.isThirdParty(ph, host) &&
            UrlRules.matchThirdPartyPath(u.encodedPath ?: "")
    }

    /** Balasan kosong yang "sopan" sesuai jenis resource, supaya skrip situs tidak error berantai. */
    private fun empty(u: Uri): WebResourceResponse {
        val p = (u.lastPathSegment ?: "").lowercase()
        val mime: String; val data: ByteArray
        when {
            p == "ad_status.js" -> { mime = "application/javascript"; data = "window.google_ad_status=1;".toByteArray() }
            p.endsWith(".js") || p.endsWith(".mjs") -> { mime = "application/javascript"; data = ByteArray(0) }
            p.endsWith(".css") -> { mime = "text/css"; data = ByteArray(0) }
            p.endsWith(".gif") || p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp") ->
                { mime = "image/gif"; data = pixel }
            p.endsWith(".json") -> { mime = "application/json"; data = "{}".toByteArray() }
            p.endsWith(".html") || p.endsWith(".htm") -> { mime = "text/html"; data = ByteArray(0) }
            else -> { mime = "text/plain"; data = ByteArray(0) }
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), ByteArrayInputStream(data))
    }

    private fun injectSite(v: WebView, url: String?) {
        val h = Uri.parse(url ?: return).host ?: return
        val js = Cosmetic.siteJs(h)
        if (js.isNotEmpty()) v.evaluateJavascript(js, null)
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
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true); javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // Popup: hanya klik tautan nyata (target=_blank) yang boleh, itupun bukan URL iklan; dibuka di tab ini.
        web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture) return false
                val tmp = WebView(this@BrowserActivity)
                tmp.webViewClient = object : WebViewClient() {
                    private var done = false
                    private fun take(u: Uri?) {
                        if (done || u == null) return
                        val sch = u.scheme ?: return
                        if (sch != "http" && sch != "https") return
                        done = true
                        if (!isAdRequest(u, false)) web.loadUrl(u.toString())
                        tmp.post { tmp.destroy() }
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { take(r.url); return true }
                    override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { take(if (url == null) null else Uri.parse(url)) }
                }
                val t = resultMsg.obj as WebView.WebViewTransport
                t.webView = tmp; resultMsg.sendToTarget()
                return true
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (r.isForMainFrame) return null
                val u = r.url
                return if (isAdRequest(u, true)) empty(u) else null
            }
            // Cegah redirect/navigasi ke domain iklan dan skema aneh (intent://, market://) tanpa klik pengguna.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url
                val sch = u.scheme ?: return false
                if (sch in passSchemes) return false
                if (sch != "http" && sch != "https") {
                    if (r.hasGesture() && sch in extSchemes) { try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { } }
                    return true
                }
                if (isAdRequest(u, false)) return true
                if (r.isForMainFrame) pageHost = u.host ?: pageHost
                return false
            }
            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                pageHost = Uri.parse(url).host ?: ""
                if (!docStart) v.evaluateJavascript(script, null)
                injectSite(v, url); bar.setText(url)
            }
            override fun onPageCommitVisible(v: WebView, url: String) { injectSite(v, url) }
            override fun onPageFinished(v: WebView, url: String) {
                if (!docStart) v.evaluateJavascript(script, null)
                injectSite(v, url)
            }
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
