package com.aegis.adblock

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Filter kosmetik (menyembunyikan elemen iklan).
 * Format baris di cosmetic.txt:
 *   selektor                  -> berlaku di semua situs (generik)
 *   situs.com,lain.id##selektor -> hanya di situs tersebut (dan subdomainnya), format EasyList
 *   ! komentar  /  # komentar (diawali "# ")
 */
object Cosmetic {
    @Volatile private var generic: List<String> = emptyList()
    @Volatile private var perSite: Map<String, List<String>> = emptyMap()

    /** Selektor bawaan: YouTube + pola iklan umum (termasuk istilah Indonesia "iklan"). */
    private val BUILTIN = listOf(
        ".ytp-ad-overlay-container", ".ytp-ad-module", ".video-ads", "ytd-ad-slot-renderer",
        "ytd-promoted-sparkles-web-renderer", "ytd-display-ad-renderer", "ytd-banner-promo-renderer",
        "#masthead-ad", "ytm-promoted-sparkles-web-renderer", "ytm-companion-ad-renderer",
        "ytd-in-feed-ad-layout-renderer", "ytd-player-legacy-desktop-watch-ads-renderer",
        "ytm-promoted-video-renderer", ".ad-container", ".ad-div", "ytd-statement-banner-renderer",
        "ytd-enforcement-message-view-model", "tp-yt-iron-overlay-backdrop.opened",
        ".ytp-ad-survey", "ytm-ad-slot-renderer", "ytd-companion-slot-renderer",
        "[id^=\"google_ads_iframe\"]", "[id^=\"aswift_\"]", "[data-google-query-id]",
        "[data-ad-slot]", "[data-ad-client]", "[data-ad-unit]", ".iklan", ".banner-iklan",
        "[class^=\"iklan-\"]", "[id^=\"iklan-\"]",
        "#player-ads", ".ytp-ad-player-overlay", ".ytp-ad-image-overlay", ".ytp-ad-text-overlay",
        "ytd-action-companion-ad-renderer", "ytd-promoted-video-renderer", "ytd-search-pyv-renderer",
        "ytm-promoted-sparkles-text-search-renderer", "ytm-companion-slot", "ytd-ad-inline-playback-meta-block",
        "ytm-rich-item-renderer:has(ytm-ad-slot-renderer)", "ytd-rich-item-renderer:has(ytd-ad-slot-renderer)",
        "ytd-reel-video-renderer:has(ytd-ad-slot-renderer)", "ytm-reel-video-renderer:has(ytm-ad-slot-renderer)"
    )

    private fun ok(sel: String) = sel.isNotEmpty() && sel.length < 200 &&
        sel.indexOf('{') < 0 && sel.indexOf('}') < 0 && sel.indexOf('<') < 0

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "cosmetic.txt")
        val lines = if (f.exists() && f.length() > 0) f.readLines()
                    else ctx.assets.open("cosmetic_seed.txt").bufferedReader().use { it.readLines() }
        val g = ArrayList<String>()
        val m = HashMap<String, ArrayList<String>>()
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("!") || l.startsWith("# ")) continue
            if (l.contains("#@#") || l.contains("#?#") || l.contains("#\$#")) continue
            val i = l.indexOf("##")
            if (i < 0) { if (ok(l) && g.size < 8000) g.add(l); continue }
            val sel = l.substring(i + 2)
            if (!ok(sel)) continue
            if (i == 0) { if (g.size < 8000) g.add(sel); continue }
            for (d in l.substring(0, i).split(',')) {
                val dd = d.trim().lowercase()
                if (dd.isEmpty() || dd.startsWith("~") || dd.contains('*')) continue
                m.getOrPut(dd) { ArrayList() }.add(sel)
            }
        }
        generic = g; perSite = m
    }

    /** CSS generik (semua situs). */
    fun genericCss(): String = (BUILTIN + generic).joinToString("\n") { "$it{display:none!important}" }

    /** JS yang menyuntikkan CSS khusus untuk host ini (kosong bila tidak ada aturan). */
    fun siteJs(host: String): String {
        val m = perSite
        if (m.isEmpty()) return ""
        val sels = ArrayList<String>()
        var d = host.lowercase()
        while (true) {
            m[d]?.let { sels.addAll(it) }
            val i = d.indexOf('.')
            if (i < 0) break
            d = d.substring(i + 1)
        }
        if (sels.isEmpty()) return ""
        val css = sels.take(3000).joinToString("\n") { "$it{display:none!important}" }
        return "window.__aegisCss&&window.__aegisCss('site'," + JSONObject.quote(css) + ");"
    }
}
