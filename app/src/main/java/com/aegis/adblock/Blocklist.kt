package com.aegis.adblock

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object Blocklist {
    @Volatile private var blocked: DomainSet = DomainSet.EMPTY
    @Volatile private var assetAllowed: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()
    @Volatile private var userBlocked: Set<String> = emptySet()

    /** Mode ketat: heuristik nama iklan (ads.xxx.com, adserver.xxx.com, ...). */
    @Volatile var strict = true
    /** Blokir hostname DoH publik (diatur service; mati bila user memakai Private DNS hostname sendiri). */
    @Volatile var blockDoh = false

    const val DOH_CANARY = "use-application-dns.net"
    private val DOH = hashSetOf(
        DOH_CANARY, "dns.google", "dns.google.com", "cloudflare-dns.com", "mozilla.cloudflare-dns.com",
        "one.one.one.one", "dns.quad9.net", "doh.opendns.com", "dns.nextdns.io", "doh.dns.sb",
        "doh.cleanbrowsing.org", "dns.alidns.com", "doh.pub", "doh.mullvad.net", "doh.libredns.gr"
    )

    // Label subdomain yang hampir pasti iklan/pelacak. Hanya dicek pada label SUBDOMAIN, bukan nama domain
    // terdaftar, sehingga "leads.com" / "adobe.com" aman, tapi "ads.situs.com" / "adserver.situs.co.id" kena.
    private val AD_LABEL = Regex(
        "(ads?|adv|adsrv|adserv(er|ice)?s?|adsystem|adtech|adnet(work)?|adtrack(ing)?|adclick|adimg|adx|" +
        "pagead\\d*|pubads|adverts?|advertising|popunder|popads?|telemetry|beacon|pixel)\\d{0,3}"
    )
    private val AD_LABEL2 = Regex(
        "ads?[-_](server|service|delivery|track|click|serve|api|cdn|img|network|exchange|manager|engine|stat)s?\\d{0,3}"
    )
    private val SLD = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "go", "web", "sch", "my", "biz", "ne", "mil")
    private val VALID = Regex("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")

    private fun clean(lines: Sequence<String>) = lines.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun parse(lines: Sequence<String>) = lines.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "blocklist.txt")
        val b = if (f.exists() && f.length() > 0) f.useLines { DomainSet.build(clean(it)) }
                else ctx.assets.open("seed.txt").bufferedReader().useLines { DomainSet.build(clean(it)) }
        assetAllowed = ctx.assets.open("allow.txt").bufferedReader().useLines { parse(it) }
        blocked = b
        strict = ctx.getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE).getBoolean(Prefs.STRICT, true)
        reloadUser(ctx)
    }

    fun size() = blocked.size

    // ---------- aturan buatan pengguna ----------
    private fun customFile(ctx: Context, allow: Boolean) = File(ctx.filesDir, if (allow) "custom_allow.txt" else "custom_block.txt")

    fun customList(ctx: Context, allow: Boolean): List<String> {
        val f = customFile(ctx, allow)
        return if (!f.exists()) emptyList() else f.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    // ---------- izin sementara (manual "Ada yang rusak?" atau otomatis oleh Learner) ----------
    class Temp(val exp: Long, val auto: Boolean)
    private val temp = ConcurrentHashMap<String, Temp>()
    private fun tempFile(ctx: Context) = File(ctx.filesDir, "temp_allow.txt")
    private fun tempOn(d: String, now: Long): Boolean { val t = temp[d]; return t != null && t.exp > now }

    private fun loadTemp(ctx: Context) {
        temp.clear(); val now = System.currentTimeMillis()
        val f = tempFile(ctx); if (!f.exists()) return
        for (l in f.readLines()) {
            val p = l.split('\t'); if (p.size < 3) continue
            val e = p[1].toLongOrNull() ?: continue
            if (e > now) temp[p[0]] = Temp(e, p[2] == "auto")
        }
    }
    private fun saveTemp(ctx: Context) {
        val now = System.currentTimeMillis()
        tempFile(ctx).writeText(temp.entries.filter { it.value.exp > now }
            .joinToString("\n") { "${it.key}\t${it.value.exp}\t${if (it.value.auto) "auto" else "user"}" } + "\n")
    }

    /** Tambah izin sementara. Otomatis dibatasi 40 entri; user tidak dibatasi. Mengembalikan domain bila berhasil. */
    @Synchronized
    fun addTemp(ctx: Context, raw: String, hours: Long, auto: Boolean): String? {
        val d = normalize(raw) ?: return null
        val now = System.currentTimeMillis()
        if (auto && temp.count { it.value.auto && it.value.exp > now } >= 40) return null
        if (auto && tempOn(d, now)) return d
        temp[d] = Temp(now + hours * 3_600_000L, auto)
        saveTemp(ctx); DnsCache.clear()
        return d
    }

    @Synchronized
    fun removeTemp(ctx: Context, domain: String) { temp.remove(domain); saveTemp(ctx); DnsCache.clear() }

    /** (domain, sisa jam, otomatis?) untuk layar aturan. */
    fun tempList(): List<Triple<String, Long, Boolean>> {
        val now = System.currentTimeMillis()
        return temp.entries.filter { it.value.exp > now }.sortedBy { it.key }
            .map { Triple(it.key, (it.value.exp - now) / 3_600_000L + 1, it.value.auto) }
    }

    private fun reloadUser(ctx: Context) {
        loadTemp(ctx)
        userBlocked = customList(ctx, false).toHashSet()
        val a = HashSet<String>(assetAllowed)
        a.addAll(customList(ctx, true))
        allowed = a
    }

    /** Ubah input bebas (URL / domain / *.domain) jadi domain bersih, atau null bila tidak valid. */
    fun normalize(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty()) return null
        if (s.contains("://")) s = Uri.parse(s).host ?: return null
        s = s.substringBefore('/').substringBefore(':').removePrefix("*.").trim('.')
        return if (VALID.matches(s)) s else null
    }

    @Synchronized
    fun addCustom(ctx: Context, allow: Boolean, raw: String): String? {
        val d = normalize(raw) ?: return null
        val cur = customList(ctx, allow)
        if (d !in cur) customFile(ctx, allow).writeText((cur + d).joinToString("\n") + "\n")
        reloadUser(ctx); DnsCache.clear()
        return d
    }

    @Synchronized
    fun removeCustom(ctx: Context, allow: Boolean, domain: String) {
        val left = customList(ctx, allow) - domain
        customFile(ctx, allow).writeText(if (left.isEmpty()) "" else left.joinToString("\n") + "\n")
        reloadUser(ctx); DnsCache.clear()
    }

    // ---------- pencocokan ----------
    /**
     * Cocokkan domain dan semua parent-nya (sub.ads.com -> ads.com). Allowlist menang.
     * [heuristics]=false dipakai untuk target CNAME agar CDN sah tidak terkena tebakan nama.
     */
    fun isBlocked(name: String, heuristics: Boolean = true): Boolean {
        var d = name.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        val a = allowed; val b = blocked; val ub = userBlocked; val doh = blockDoh
        val now = System.currentTimeMillis()
        var hit = false
        while (true) {
            if (d in a || tempOn(d, now)) return false
            if (b.contains(d) || d in ub) hit = true
            if (doh && d in DOH) hit = true
            val i = d.indexOf('.')
            if (i < 0) break
            d = d.substring(i + 1)
        }
        return hit || (heuristics && strict && looksLikeAd(name))
    }

    /** True bila domain (atau parent-nya) ada di allowlist; dipakai agar izin eksplisit user menang atas deteksi CNAME. */
    fun isAllowed(name: String): Boolean {
        var d = name.lowercase().trimEnd('.')
        val a = allowed; val now = System.currentTimeMillis()
        while (d.isNotEmpty()) {
            if (d in a || tempOn(d, now)) return true
            val i = d.indexOf('.'); if (i < 0) break
            d = d.substring(i + 1)
        }
        return false
    }

    /** Label subdomain pertama yang memicu heuristik, atau null bila tidak ada. */
    fun heuristicLabel(name: String): String? {
        val labels = name.lowercase().trimEnd('.').split('.')
        val n = labels.size
        if (n < 3) return null
        val tail = if (labels[n - 1].length == 2 && labels[n - 2] in SLD) 3 else 2
        for (i in 0 until n - tail) {
            val l = labels[i]
            if (AD_LABEL.matches(l) || AD_LABEL2.matches(l)) return l
        }
        return null
    }
    private fun looksLikeAd(name: String) = heuristicLabel(name) != null
}

/** Statistik per-domain untuk layar log: membantu menemukan domain yang salah blokir. */
object Stats {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val recent = java.util.ArrayDeque<String>()

    fun note(name: String) {
        val n = name.lowercase().trimEnd('.')
        if (counts.size > 3000) counts.clear()
        counts.getOrPut(n) { AtomicInteger() }.incrementAndGet()
        synchronized(recent) {
            recent.remove(n); recent.addFirst(n)
            while (recent.size > 80) recent.removeLast()
        }
    }

    class Event(val ts: Long, val name: String, val heur: Boolean, val pkg: String?)
    private val events = java.util.ArrayDeque<Event>()
    fun addEvent(e: Event) { synchronized(events) { events.addFirst(e); while (events.size > 400) events.removeLast() } }
    fun pkgOf(name: String): String? { val n = name.lowercase().trimEnd('.'); return synchronized(events) { events.firstOrNull { it.name == n && it.pkg != null }?.pkg } }
    fun eventsSince(ms: Long): List<Event> {
        val t = System.currentTimeMillis() - ms
        return synchronized(events) { events.filter { it.ts >= t } }
    }
    /** Berapa kali [name] diminta [pkg] dalam [windowMs] terakhir (tanda aplikasi mengulang karena gagal). */
    fun burst(name: String, pkg: String, windowMs: Long): Int {
        val n = name.lowercase().trimEnd('.'); val t = System.currentTimeMillis() - windowMs
        return synchronized(events) { events.count { it.ts >= t && it.name == n && it.pkg == pkg } }
    }

    private val heur = ConcurrentHashMap.newKeySet<String>()
    /** Tandai domain yang diblokir hanya oleh heuristik nama (bukan oleh daftar) -> kandidat salah blokir. */
    fun markHeur(name: String) { if (heur.size > 2000) heur.clear(); heur.add(name.lowercase().trimEnd('.')) }
    fun isHeur(name: String) = heur.contains(name)

    fun recent(): List<String> = synchronized(recent) { recent.toList() }
    fun count(name: String): Int = counts[name]?.get() ?: 0
}
