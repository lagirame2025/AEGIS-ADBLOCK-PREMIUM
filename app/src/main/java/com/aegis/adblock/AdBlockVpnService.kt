package com.aegis.adblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.SystemClock
import java.net.Inet4Address
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * DNS-filtering VPN service. Runs only while the user has it switched on:
 * - User can stop it anytime from this notification, from the app, or from
 *   Android Settings > Apps > Aegis AdBlock > Force stop / Settings > VPN.
 * - Android calls onRevoke() the moment the user turns off the VPN from
 *   system Settings, and we stop cleanly there.
 * - It does NOT relaunch itself after being stopped by the user. It only
 *   resumes after a device reboot, and only if the user has opted in via
 *   the "autostart after restart" toggle in the app (see BootReceiver).
 *
 * Lapisan filter (urut): blocklist + allowlist + aturan user -> heuristik nama iklan (mode ketat)
 * -> cache -> upstream (dengan fallback) -> deteksi CNAME cloaking pada jawaban.
 */
class AdBlockVpnService : VpnService() {
    companion object {
        @Volatile var running = false
        val blockedCount = AtomicInteger(); val totalCount = AtomicInteger()
        val cacheHits = AtomicInteger(); val staleServed = AtomicInteger(); val hedged = AtomicInteger()
        const val FAKE_DNS = "10.111.0.1"

        /** Cache berumur panjang untuk domain yang terbukti CNAME-cloaking (menghindari query ulang tiap kali). */
        private const val CLOAK_TTL = 600L
        /** Entri cache yang sudah kedaluwarsa masih boleh disajikan selama ini (detik) sambil di-refresh. */
        private const val STALE_SEC = 3600L
        private val BLOCK_MARK = byteArrayOf(1)

        /** DNS publik yang sering di-hardcode aplikasi untuk menghindari filter; kita tangkap lewat tunnel. */
        private val HIJACK = arrayOf(
            "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220"
        )
        private const val CHANNEL_ID = "aegis_vpn_status"
        private const val NOTIF_ID = 42
    }
    private var tun: ParcelFileDescriptor? = null
    // Antrian dibatasi: saat banjir query, yang paling lama dibuang (klien toh sudah menyerah/mengulang),
    // sehingga memori tidak membengkak dan query baru tetap dilayani.
    private val pool = ThreadPoolExecutor(16, 16, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(512),
        ThreadPoolExecutor.DiscardOldestPolicy())

    /** Satu upstream DNS dengan skor kesehatan: latensi (EWMA) + bias preferensi + penalti kegagalan beruntun. */
    private class Upstream(val host: String, val bias: Double) {
        val addr: InetAddress = InetAddress.getByName(host) // literal IP, tidak memicu lookup
        @Volatile var ewma = 80.0
        @Volatile var fails = 0
        @Volatile var lastFail = 0L
        fun score(now: Long): Double {
            if (fails > 0 && now - lastFail > 60_000L) fails = 0 // beri kesempatan lagi setelah 1 menit
            return ewma + bias + fails * 1500.0
        }
        fun ok(ms: Long) { ewma = ewma * 0.8 + ms * 0.2; fails = 0 }
        fun fail() { fails = minOf(fails + 1, 5); lastFail = SystemClock.elapsedRealtime() }
    }

    // AdGuard DNS diutamakan (lapisan filter tambahan), Cloudflare & Quad9 cadangan; DNS bawaan jaringan (operator/
    // router) terakhir, berguna di jaringan yang memblokir DNS publik.
    private val base = listOf(Upstream("94.140.14.14", 0.0), Upstream("1.1.1.1", 25.0), Upstream("9.9.9.9", 40.0))
    private val sysUps = ConcurrentHashMap<Network, List<Upstream>>()
    private var netCb: ConnectivityManager.NetworkCallback? = null

    private val tcpDns = TcpDns({ q -> answer(q, true) { null } }, { r -> pool.execute(r) })

    private class Flight { val latch = CountDownLatch(1); @Volatile var result: ByteArray? = null }
    private val inflight = ConcurrentHashMap<String, Flight>()

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stop(); return START_NOT_STICKY }
        if (running) return START_STICKY
        Blocklist.load(this)
        DnsCache.clear()
        // Bila user memakai Private DNS "hostname" sendiri, jangan ganggu DoT/DoH-nya (bisa memutus internet).
        val ownPrivateDns = try {
            Settings.Global.getString(contentResolver, "private_dns_mode") == "hostname"
        } catch (_: Exception) { false }
        val advanced = Blocklist.strict && !ownPrivateDns
        Blocklist.blockDoh = advanced
        val b = Builder().setSession("Aegis AdBlock").addAddress("10.111.0.2", 32)
            .addDnsServer(FAKE_DNS).addRoute(FAKE_DNS, 32).setMtu(1500)
        if (advanced) for (ip in HIJACK) { try { b.addRoute(ip, 32) } catch (_: Exception) { } }
        // Aplikasi yang dikecualikan user (mis. bank/streaming yang rewel) tidak lewat tunnel sama sekali.
        val skip = getSharedPreferences("aegis_prefs", MODE_PRIVATE).getStringSet(Prefs.BYPASS, emptySet()) ?: emptySet()
        for (pkg in skip) { try { b.addDisallowedApplication(pkg) } catch (_: Exception) { } }
        val pfd = b.establish() ?: return START_NOT_STICKY
        tun = pfd; running = true
        startForeground(NOTIF_ID, buildNotification())
        watchSystemDns()
        Thread { loop(pfd) }.start()
        return START_STICKY
    }

    /** Ongoing, dismiss-proof-by-swipe notification — but it carries its own
     * "Matikan" action so the user is always one tap away from stopping the
     * service, exactly as Android's foreground-service guidelines require. */
    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Status proteksi Aegis", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Menampilkan status aktif/mati dari proteksi iklan Aegis"
            nm.createNotificationChannel(ch)
        }
        val stopIntent = Intent(this, AdBlockVpnService::class.java).setAction("STOP")
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
                 else Notification.Builder(this)
        return nb
            .setContentTitle("Aegis AdBlock aktif")
            .setContentText("Proteksi DNS berjalan. Ketuk untuk membuka, atau matikan di bawah.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun loop(pfd: ParcelFileDescriptor) {
        val inp = FileInputStream(pfd.fileDescriptor); val out = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = inp.read(buf); if (n <= 0) { Thread.sleep(20); continue }
                // Satu paket cacat TIDAK boleh mematikan seluruh VPN: tangani per paket.
                try { dispatch(buf, n, out) } catch (_: Exception) { }
            }
        } catch (_: Exception) { } finally { if (running) stop() }
    }

    private fun dispatch(buf: ByteArray, n: Int, out: FileOutputStream) {
        if (n < 28 || (buf[0].toInt() shr 4) != 4) return
        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || n < ihl + 8) return
        val proto = buf[9].toInt() and 0xFF
        val pkt = buf.copyOf(n)
        val dport = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        if (proto == 6) {
            // DNS-over-TCP (port 53) dilayani sendiri; DoT/DoH (853/443) ke server DNS yang kita tangkap ditolak cepat.
            if (dport == 53) tcpDns.onSegment(pkt, n, ihl) { synchronized(out) { out.write(it) } }
            else Dns.tcpReset(pkt, ihl)?.let { synchronized(out) { out.write(it) } }
            return
        }
        if (proto != 17) return
        if (dport != 53) return // UDP 443/853 (QUIC/DoQ) ke DNS publik: dibuang, aplikasi jatuh balik ke DNS biasa
        pool.execute { handle(pkt, ihl, out) }
    }

    private fun handle(pkt: ByteArray, ihl: Int, out: FileOutputStream) {
        try {
            val udpLen = ((pkt[ihl + 4].toInt() and 0xFF) shl 8) or (pkt[ihl + 5].toInt() and 0xFF)
            val endPos = if (udpLen >= 8) minOf(pkt.size, ihl + udpLen) else pkt.size
            val dns = pkt.copyOfRange(ihl + 8, endPos)
            val reply = answer(dns, false) { Attribution.pkgFor(this, pkt, ihl, 17) } ?: return
            val resp = Dns.wrapUdp(pkt, ihl, reply)
            synchronized(out) { out.write(resp) }
        } catch (_: Exception) { }
    }

    /** Inti penjawab untuk satu pesan DNS (dipakai jalur UDP dan TCP). Null bila pesan tidak valid. */
    private fun answer(dns: ByteArray, viaTcp: Boolean, pkgOf: () -> String?): ByteArray? {
        val q = Dns.question(dns) ?: return null
        val name = q.first; val end = q.second; val qtype = q.third
        totalCount.incrementAndGet()
        if (!Blocklist.isBlocked(name)) return resolve(name, dns, end, qtype, viaTcp)
        val heurOnly = !Blocklist.isBlocked(name, heuristics = false)
        if (heurOnly) Stats.markHeur(name)
        // Atribusi aplikasi itu mahal: hanya untuk tebakan heuristik dan kemunculan pertama domain daftar.
        val pkg = if (heurOnly || Stats.count(name.lowercase().trimEnd('.')) < 2) pkgOf() else null
        Learner.onBlocked(this, name, heurOnly, pkg)
        return blockReply(name, dns, end, qtype)
    }

    private fun blockReply(name: String, dns: ByteArray, end: Int, qtype: Int): ByteArray {
        blockedCount.incrementAndGet(); Stats.note(name)
        // Canary Firefox harus NXDOMAIN supaya Firefox mematikan DoH bawaannya.
        if (name.equals(Blocklist.DOH_CANARY, true)) return Dns.nxReply(dns, end)
        // Aplikasi yang terus-menerus mengulang domain terblokir menguras baterai: TTL panjang agar klien berhenti menanyakan.
        val ttl = if (Stats.count(name.lowercase().trimEnd('.')) > 20) 900 else 60
        return Dns.blockedReply(dns, end, qtype, ttl)
    }

    private fun resolve(name: String, dns: ByteArray, end: Int, qtype: Int, viaTcp: Boolean): ByteArray {
        val key = name.lowercase() + "/" + qtype
        // Domain yang sudah terbukti CNAME-cloaking: langsung blokir tanpa menghubungi upstream.
        if (DnsCache.get("B/" + key) != null) return blockReply(name, dns, end, qtype)
        val hit = DnsCache.get(key)
        if (hit != null) {
            cacheHits.incrementAndGet()
            val r = Dns.withTtl(hit.data, hit.ttlLeft)
            r[0] = dns[0]; r[1] = dns[1]
            if (hit.stale || hit.prefetch) { // sajikan dulu, perbarui di latar belakang
                if (hit.stale) staleServed.incrementAndGet()
                val copy = dns.copyOf()
                pool.execute { try { fetchOnce(key, name, copy, end, qtype, false) } catch (_: Exception) { } }
            }
            return r
        }
        return fetchOnce(key, name, dns, end, qtype, viaTcp)
    }

    /** Singleflight: query identik yang sedang berjalan digabung jadi satu permintaan upstream. */
    private fun fetchOnce(key: String, name: String, dns: ByteArray, end: Int, qtype: Int, viaTcp: Boolean): ByteArray {
        val fk = if (viaTcp) "$key|t" else key
        val mine = Flight()
        val prior = inflight.putIfAbsent(fk, mine)
        if (prior != null) {
            prior.latch.await(3500, TimeUnit.MILLISECONDS)
            prior.result?.let { val r = it.copyOf(); r[0] = dns[0]; r[1] = dns[1]; return r }
            return queryUpstreams(key, name, dns, end, qtype, viaTcp) // pemimpin gagal/lambat: coba sendiri
        }
        try {
            val r = queryUpstreams(key, name, dns, end, qtype, viaTcp)
            mine.result = r
            return r
        } finally { inflight.remove(fk, mine); mine.latch.countDown() }
    }

    private fun queryUpstreams(key: String, name: String, dns: ByteArray, end: Int, qtype: Int, viaTcp: Boolean): ByteArray {
        val ups = pickUpstreams()
        val r = (if (viaTcp) raceTcp(dns, ups) else race(dns, ups)) ?: return Dns.failReply(dns, end)
        val a = Dns.parseAnswer(r) ?: return r
        // CNAME cloaking: domain "first-party" yang ternyata alias ke domain pelacak/iklan. Izin eksplisit user menang.
        if (a.cnames.isNotEmpty() && !Blocklist.isAllowed(name)) {
            for (c in a.cnames) {
                if (Blocklist.isBlocked(c, heuristics = false)) {
                    DnsCache.put("B/" + key, BLOCK_MARK, CLOAK_TTL)
                    return blockReply(name, dns, end, qtype)
                }
            }
        }
        // Balasan besar dari TCP tidak di-cache: klien UDP tanpa EDNS tidak bisa menerimanya.
        if (!a.truncated && !(viaTcp && r.size > 512)) {
            if (a.rcode == 0 && a.answers > 0) DnsCache.put(key, r, a.minTtl.coerceIn(10L, 600L), STALE_SEC)
            // Negative caching: NXDOMAIN / NODATA (sering untuk AAAA & HTTPS) tidak perlu ditanyakan ulang.
            else if (a.rcode == 3 || (a.rcode == 0 && a.answers == 0))
                DnsCache.put(key, r, (if (a.negTtl > 0) a.negTtl else 30L).coerceIn(10L, 300L))
        }
        return r
    }

    /** Upstream lewat TCP (RFC 7766) untuk query yang masuk lewat TCP, supaya balasan besar tidak terpotong. */
    private fun raceTcp(dns: ByteArray, ups: List<Upstream>): ByteArray? {
        for (u in ups.take(3)) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                java.net.Socket().use { sk ->
                    protect(sk); sk.soTimeout = 2500
                    sk.connect(java.net.InetSocketAddress(u.addr, 53), 2000)
                    val o = sk.getOutputStream()
                    o.write(byteArrayOf((dns.size shr 8).toByte(), dns.size.toByte())); o.write(dns); o.flush()
                    val inp = java.io.DataInputStream(sk.getInputStream())
                    val b = ByteArray(inp.readUnsignedShort()); inp.readFully(b)
                    if (b.size >= 12 && b[0] == dns[0] && b[1] == dns[1]) { u.ok(SystemClock.elapsedRealtime() - t0); return b }
                }
            } catch (_: Exception) { }
            u.fail()
        }
        return null
    }

    private fun pickUpstreams(): List<Upstream> {
        val now = SystemClock.elapsedRealtime()
        val sys = sysUps.values.flatten().filter { s -> base.none { it.host == s.host } }.distinctBy { it.host }
        return (base + sys).sortedBy { it.score(now) }.take(4)
    }

    /**
     * Balapan upstream dengan hedging: kirim ke yang tercepat, bila belum menjawab dalam ~1,6x latensi biasanya
     * kirim juga ke berikutnya, ambil jawaban pertama yang valid. Latensi & kegagalan dicatat untuk pemilihan
     * berikutnya, jadi satu upstream yang lambat/mati tidak membuat tiap query menunggu timeout.
     */
    private fun race(dns: ByteArray, ups: List<Upstream>): ByteArray? {
        if (ups.isEmpty()) return null
        val socks = ArrayList<DatagramSocket>(); val used = ArrayList<Upstream>(); val starts = ArrayList<Long>()
        val buf = ByteArray(4096); val pkt = DatagramPacket(buf, buf.size)
        var launched = 0
        fun launch() {
            val u = ups[launched++]
            val sk = DatagramSocket()
            try {
                protect(sk); sk.soTimeout = 15
                sk.send(DatagramPacket(dns, dns.size, u.addr, 53))
                socks.add(sk); used.add(u); starts.add(SystemClock.elapsedRealtime())
            } catch (_: Exception) { u.fail(); try { sk.close() } catch (_: Exception) { } }
        }
        val t0 = SystemClock.elapsedRealtime()
        try {
            launch()
            var hedgeAt = t0 + (ups[0].ewma * 1.6).toLong().coerceIn(120L, 600L)
            val deadline = t0 + 3000L
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) break
                if ((now >= hedgeAt || socks.isEmpty()) && launched < ups.size) {
                    launch(); hedgeAt = now + 400L; if (socks.size > 1) hedged.incrementAndGet()
                }
                if (socks.isEmpty()) { if (launched >= ups.size) break else continue }
                for (i in socks.indices) {
                    try {
                        pkt.length = buf.size
                        socks[i].receive(pkt)
                        val out = buf.copyOf(pkt.length)
                        // Abaikan balasan dengan ID tidak cocok (anti-spoof sederhana)
                        if (out.size >= 12 && out[0] == dns[0] && out[1] == dns[1]) {
                            used[i].ok(SystemClock.elapsedRealtime() - starts[i])
                            if (i > 0) used[0].fail() // yang pertama kalah dari hedge: turunkan skornya
                            return out
                        }
                    } catch (_: SocketTimeoutException) { }
                }
            }
            for (u in used) u.fail()
            return null
        } finally { for (sk in socks) try { sk.close() } catch (_: Exception) { } }
    }

    /** Kumpulkan DNS bawaan jaringan fisik (non-VPN) sebagai upstream cadangan terakhir. */
    private fun watchSystemDns() {
        try {
            val cm = (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(n: Network, lp: android.net.LinkProperties) {
                    val l = lp.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { a ->
                        try { Upstream(a.hostAddress ?: return@mapNotNull null, 200.0) } catch (_: Exception) { null }
                    }
                    sysUps[n] = l
                    DnsCache.clear() // jaringan berganti: jawaban lama (mis. CDN regional) bisa tidak relevan
                }
                override fun onLost(n: Network) { sysUps.remove(n) }
            }
            cm.registerNetworkCallback(req, cb); netCb = cb
        } catch (_: Exception) { }
    }

    private fun unwatchSystemDns() {
        try { netCb?.let { (getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) { }
        netCb = null; sysUps.clear()
    }

    private fun stop() {
        running = false; unwatchSystemDns(); tun?.close(); tun = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }
    // Called by Android the instant the user revokes VPN permission from
    // system Settings. We must stop immediately here — never resist this.
    override fun onRevoke() { stop() }
    override fun onDestroy() { running = false; unwatchSystemDns(); tun?.close(); super.onDestroy() }
}
