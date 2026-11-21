package com.aegis.adblock

object Dns {
    /** Hasil parsing jawaban DNS: kode respons, jumlah jawaban, TTL terkecil, dan semua target CNAME. */
    class Answer(val rcode: Int, val answers: Int, val minTtl: Long, val cnames: List<String>, val negTtl: Long = 0L, val truncated: Boolean = false)

    private fun u16(d: ByteArray, p: Int) = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)

    /** Return (nama, offsetAkhirQuestion, qtype) */
    fun question(d: ByteArray): Triple<String, Int, Int>? {
        if (d.size < 17 || ((d[4].toInt() shl 8) or (d[5].toInt() and 0xFF)) < 1) return null
        var p = 12; val sb = StringBuilder()
        while (p < d.size) {
            val l = d[p].toInt() and 0xFF
            if (l == 0) { p++; break }
            if (l and 0xC0 != 0 || p + l >= d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, p + 1, l, Charsets.ISO_8859_1)); p += l + 1
        }
        if (p + 4 > d.size) return null
        val qtype = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
        return Triple(sb.toString(), p + 4, qtype)
    }

    /** Baca satu nama (mendukung kompresi pointer). Return (nama, offset setelah nama di posisi asal). */
    private fun readName(d: ByteArray, start: Int): Pair<String, Int>? {
        var p = start; var next = -1; var jumps = 0
        val sb = StringBuilder()
        while (true) {
            if (p >= d.size) return null
            val l = d[p].toInt() and 0xFF
            if (l == 0) { p++; break }
            if ((l and 0xC0) == 0xC0) {
                if (p + 1 >= d.size) return null
                if (next < 0) next = p + 2
                p = ((l and 0x3F) shl 8) or (d[p + 1].toInt() and 0xFF)
                jumps++
                if (jumps > 16) return null
                continue
            }
            if ((l and 0xC0) != 0 || p + 1 + l > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, p + 1, l, Charsets.ISO_8859_1)); p += l + 1
        }
        return Pair(sb.toString(), if (next >= 0) next else p)
    }

    /** Parse balasan upstream untuk mendeteksi CNAME cloaking & menentukan TTL cache. */
    fun parseAnswer(d: ByteArray): Answer? {
        if (d.size < 12) return null
        val rcode = d[3].toInt() and 0x0F
        val qd = u16(d, 4); val an = u16(d, 6)
        var p = 12
        for (k in 0 until qd) {
            val r = readName(d, p) ?: return null
            p = r.second + 4
        }
        var minTtl = Long.MAX_VALUE
        val cn = ArrayList<String>()
        for (k in 0 until an) {
            val r = readName(d, p) ?: return null
            p = r.second
            if (p + 10 > d.size) return null
            val type = u16(d, p)
            val ttl = (u16(d, p + 4).toLong() shl 16) or u16(d, p + 6).toLong()
            val rdlen = u16(d, p + 8)
            p += 10
            if (p + rdlen > d.size) return null
            if (ttl < minTtl) minTtl = ttl
            if (type == 5) { val t = readName(d, p); if (t != null) cn.add(t.first) }
            p += rdlen
        }
        // Negative caching (RFC 2308): NXDOMAIN/NODATA boleh di-cache selama min(TTL SOA, field MINIMUM SOA).
        var neg = 0L
        val ns = u16(d, 8)
        for (k in 0 until ns) {
            val r = readName(d, p) ?: break
            p = r.second
            if (p + 10 > d.size) break
            val type = u16(d, p); val ttl = (u16(d, p + 4).toLong() shl 16) or u16(d, p + 6).toLong()
            val rdlen = u16(d, p + 8); p += 10
            if (p + rdlen > d.size) break
            if (type == 6) {
                val m = readName(d, p); val rn = m?.let { readName(d, it.second) }
                if (rn != null && rn.second + 20 <= d.size) {
                    val q = rn.second + 16
                    val minimum = (u16(d, q).toLong() shl 16) or u16(d, q + 2).toLong()
                    neg = minOf(ttl, minimum)
                }
            }
            p += rdlen
        }
        val tc = (d[2].toInt() and 0x02) != 0
        return Answer(rcode, an, if (minTtl == Long.MAX_VALUE) 0L else minTtl, cn, neg, tc)
    }

    /** Jawab A=0.0.0.0, AAAA=::, tipe lain: kosong (NOERROR). */
    fun blockedReply(q: ByteArray, end: Int, qtype: Int, ttl: Int = 60): ByteArray {
        val head = q.copyOf(end)
        head[2] = 0x81.toByte(); head[3] = 0x80.toByte()
        for (k in 6..11) head[k] = 0
        val rdlen = when (qtype) { 1 -> 4; 28 -> 16; else -> return head }
        head[7] = 1
        val ans = ByteArray(12 + rdlen)
        ans[0] = 0xC0.toByte(); ans[1] = 0x0C
        ans[3] = qtype.toByte(); ans[5] = 1; ans[8] = (ttl shr 8).toByte(); ans[9] = ttl.toByte(); ans[11] = rdlen.toByte()
        return head + ans
    }

    /** NXDOMAIN (dipakai untuk domain canary Firefox agar DoH bawaan browser mati). */
    fun nxReply(q: ByteArray, end: Int): ByteArray {
        val h = q.copyOf(end)
        h[2] = 0x81.toByte(); h[3] = 0x83.toByte()
        for (k in 6..11) h[k] = 0
        return h
    }

    /** SERVFAIL cepat bila semua upstream gagal, supaya aplikasi tidak menunggu timeout. */
    fun failReply(q: ByteArray, end: Int): ByteArray {
        val h = q.copyOf(end)
        h[2] = 0x81.toByte(); h[3] = 0x82.toByte()
        for (k in 6..11) h[k] = 0
        return h
    }

    /**
     * Salin balasan dengan TTL setiap RR dibatasi maksimal [ttl] detik (RR OPT dilewati karena field TTL-nya
     * berisi flag EDNS). Dipakai agar balasan dari cache membawa sisa TTL yang benar, bukan TTL awal.
     */
    fun withTtl(d: ByteArray, ttl: Long): ByteArray {
        val out = d.copyOf()
        if (d.size < 12) return out
        var p = 12
        val qd = u16(d, 4); val total = u16(d, 6) + u16(d, 8) + u16(d, 10)
        for (k in 0 until qd) { val r = readName(d, p) ?: return out; p = r.second + 4 }
        for (k in 0 until total) {
            val r = readName(d, p) ?: return out
            p = r.second
            if (p + 10 > d.size) return out
            val type = u16(d, p); val rdlen = u16(d, p + 8)
            if (type != 41) {
                val old = (u16(d, p + 4).toLong() shl 16) or u16(d, p + 6).toLong()
                val nt = minOf(old, ttl)
                out[p + 4] = (nt shr 24).toByte(); out[p + 5] = (nt shr 16).toByte()
                out[p + 6] = (nt shr 8).toByte(); out[p + 7] = nt.toByte()
            }
            p += 10 + rdlen
            if (p > d.size) return out
        }
        return out
    }

    private fun csum(b: ByteArray, len: Int): Int {
        var s = 0L; var i = 0
        while (i < len) { s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 }
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    /** Bungkus payload DNS jadi paket IPv4/UDP balasan (src/dst ditukar). */
    fun wrapUdp(orig: ByteArray, ihl: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val r = ByteArray(total)
        r[0] = 0x45; r[2] = (total shr 8).toByte(); r[3] = total.toByte()
        r[6] = 0x40; r[8] = 64; r[9] = 17
        System.arraycopy(orig, 16, r, 12, 4); System.arraycopy(orig, 12, r, 16, 4)
        val c = csum(r, 20); r[10] = (c shr 8).toByte(); r[11] = c.toByte()
        // port sumber balasan = port tujuan asli (53), port tujuan = port sumber asli
        r[20] = orig[ihl + 2]; r[21] = orig[ihl + 3]
        r[22] = orig[ihl]; r[23] = orig[ihl + 1]
        val ul = 8 + payload.size; r[24] = (ul shr 8).toByte(); r[25] = ul.toByte()
        System.arraycopy(payload, 0, r, 28, payload.size)
        return r
    }

    /**
     * Balas SYN TCP dengan RST+ACK. Dipakai untuk DoH/DoT/DNS-over-TCP ke server DNS publik yang kita
     * tangkap, supaya aplikasi langsung jatuh balik ke DNS biasa (yang tersaring) tanpa menunggu timeout.
     */
    fun tcpReset(orig: ByteArray, ihl: Int): ByteArray? {
        if (orig.size < ihl + 20) return null
        val fl = orig[ihl + 13].toInt() and 0xFF
        if ((fl and 0x02) == 0 || (fl and 0x10) != 0) return null // hanya SYN murni
        val r = ByteArray(40)
        r[0] = 0x45; r[3] = 40; r[6] = 0x40; r[8] = 64; r[9] = 6
        System.arraycopy(orig, 16, r, 12, 4); System.arraycopy(orig, 12, r, 16, 4)
        val c = csum(r, 20); r[10] = (c shr 8).toByte(); r[11] = c.toByte()
        r[20] = orig[ihl + 2]; r[21] = orig[ihl + 3]; r[22] = orig[ihl]; r[23] = orig[ihl + 1]
        var seq = 0L
        for (k in 4..7) seq = (seq shl 8) or (orig[ihl + k].toLong() and 0xFF)
        val ack = (seq + 1) and 0xFFFFFFFFL
        r[28] = (ack shr 24).toByte(); r[29] = (ack shr 16).toByte(); r[30] = (ack shr 8).toByte(); r[31] = ack.toByte()
        r[32] = 0x50; r[33] = 0x14 // header 20 byte, flag RST|ACK
        val ps = ByteArray(32) // pseudo header + segmen TCP
        System.arraycopy(r, 12, ps, 0, 8); ps[9] = 6; ps[11] = 20
        System.arraycopy(r, 20, ps, 12, 20)
        val tc = csum(ps, 32); r[36] = (tc shr 8).toByte(); r[37] = tc.toByte()
        return r
    }
}

/**
 * Cache balasan DNS (LRU 4000 entri). Tiap entri punya masa segar (TTL) dan masa basi ([staleUntil]):
 * entri basi tetap boleh disajikan sementara di-refresh di latar belakang (serve-stale, RFC 8767), jadi
 * halaman tetap cepat dan jaringan yang putus-nyambung tidak langsung memutus resolusi nama.
 */
object DnsCache {
    class Hit(val data: ByteArray, val stale: Boolean, val ttlLeft: Long, val prefetch: Boolean = false)
    private class Entry(val data: ByteArray, val expires: Long, val staleUntil: Long, val total: Long) {
        var hits = 0; var refreshing = false
    }

    private val map = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 4000
    }

    @Synchronized fun get(key: String): Hit? {
        val e = map[key] ?: return null
        val now = System.currentTimeMillis()
        if (now > e.staleUntil) { map.remove(key); return null }
        val stale = now > e.expires
        val left = if (stale) 30L else maxOf(1L, (e.expires - now) / 1000)
        e.hits++
        // Prefetch: nama yang sering dipakai (>=3 kali) dan TTL-nya hampir habis di-refresh sebelum kedaluwarsa,
        // jadi pengguna hampir tidak pernah menunggu lookup upstream untuk situs favoritnya.
        val pre = !stale && !e.refreshing && e.hits >= 3 && left <= maxOf(3L, e.total / 10)
        if (pre) e.refreshing = true
        return Hit(e.data.copyOf(), stale, left, pre)
    }

    @Synchronized fun put(key: String, data: ByteArray, ttlSec: Long, staleSec: Long = 0L) {
        val now = System.currentTimeMillis()
        val exp = now + ttlSec * 1000
        map[key] = Entry(data.copyOf(), exp, exp + staleSec * 1000, ttlSec)
    }

    @Synchronized fun clear() { map.clear() }
}
