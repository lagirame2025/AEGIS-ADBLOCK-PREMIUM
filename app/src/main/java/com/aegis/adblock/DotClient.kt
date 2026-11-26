package com.aegis.adblock

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Klien DNS-over-TLS (RFC 7858) ke server pribadi.
 *
 * Rancangan agar tidak membuat internet lambat atau putus-putus:
 *  - SATU koneksi TLS dipakai bersama oleh semua query (pipelining, RFC 7766): ID query ditulis ulang unik per
 *    koneksi dan jawaban dicocokkan lewat ID. Satu halaman web yang memicu puluhan query tidak membuka puluhan
 *    handshake dan tidak menabrak rate limit server (nftables-dot.conf).
 *  - Pemutus sirkuit: bila server tidak terjangkau (mis. jaringan seluler memblokir port 853), query berikutnya
 *    langsung gagal tanpa menunggu timeout selama masa jeda (10 dtk, naik sampai 5 menit; mode ketat 1,5 dtk),
 *    sehingga pemanggil segera jatuh ke DNS biasa. Jeda direset saat jaringan berganti ([reset]).
 *  - Timeout pendek (konek 1,5 dtk, jawaban 2 dtk) dan koneksi menganggur ditutup setelah 20 dtk agar tidak
 *    memakai koneksi basi yang sudah dibuang NAT operator.
 *  - Sertifikat diverifikasi terhadap [host] (SNI = host), IP boleh literal.
 *
 * [protect] wajib memanggil VpnService.protect(socket) supaya lalu lintas DoT tidak masuk lagi ke tunnel kita.
 */
class DotClient(val host: String, val ip: String, private val strict: Boolean, private val protect: (Socket) -> Boolean) {
    private class Pending { val latch = CountDownLatch(1); @Volatile var reply: ByteArray? = null }

    private class Conn(val tcp: Socket, val tls: SSLSocket) {
        val input = DataInputStream(tls.inputStream.buffered())
        val output = tls.outputStream
        val pending = ConcurrentHashMap<Int, Pending>()
        @Volatile var dead = false
        @Volatile var lastUsed = System.currentTimeMillis()
        @Volatile var served = 0

        /** Tutup koneksi dan bangunkan semua query yang menunggu (mereka melihat reply == null). */
        fun fail() {
            dead = true
            try { tls.close() } catch (_: Exception) { }
            try { tcp.close() } catch (_: Exception) { }
            for (p in pending.values) p.latch.countDown()
            pending.clear()
        }
    }

    private val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
    private val lock = ReentrantLock()
    private val nextId = AtomicInteger((Math.random() * 65535).toInt())
    private val fails = AtomicInteger()
    @Volatile private var cur: Conn? = null
    @Volatile private var downUntil = 0L
    @Volatile private var closed = false

    companion object {
        private const val PORT = 853
        private const val CONNECT_MS = 1500
        private const val HANDSHAKE_MS = 2500
        private const val ANSWER_MS = 2000L
        private const val LOCK_WAIT_MS = 1000L
        private const val IDLE_MS = 20_000L
        private const val BASE_BACKOFF = 10_000L
        private const val MAX_BACKOFF = 300_000L
        private const val STRICT_BACKOFF = 1_500L
    }

    private fun open(): Conn {
        val tcp = Socket()
        try {
            if (!protect(tcp)) throw IOException("protect gagal")
            tcp.tcpNoDelay = true
            tcp.keepAlive = true
            tcp.connect(InetSocketAddress(ip, PORT), CONNECT_MS)
            tcp.soTimeout = HANDSHAKE_MS
            // Membungkus socket terhubung: createSocket(s, host, port, true) mengirim SNI = host (bukan IP).
            val tls = factory.createSocket(tcp, host, PORT, true) as SSLSocket
            tls.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session))
                throw SSLPeerUnverifiedException("sertifikat tidak cocok dengan $host")
            tls.soTimeout = 0 // pembaca menunggu tanpa batas; keterlambatan dinilai per query (ANSWER_MS)
            val c = Conn(tcp, tls)
            Thread({ readLoop(c) }, "dot-reader").apply { isDaemon = true; start() }
            return c
        } catch (e: Exception) {
            try { tcp.close() } catch (_: Exception) { }
            throw e
        }
    }

    private fun readLoop(c: Conn) {
        try {
            while (!c.dead) {
                val n = c.input.readUnsignedShort()
                if (n < 12) throw IOException("bingkai DoT tidak valid")
                val b = ByteArray(n); c.input.readFully(b)
                val id = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                c.pending.remove(id)?.let { it.reply = b; it.latch.countDown() }
            }
        } catch (_: Exception) {
        } finally { c.fail() }
    }

    private fun usable(c: Conn?, now: Long) = c != null && !c.dead && now - c.lastUsed <= IDLE_MS

    /** Koneksi aktif, atau yang baru dibuka. Null bila sedang masa jeda, server tidak terjangkau, atau antre terlalu lama. */
    private fun conn(): Conn? {
        val c = cur
        if (usable(c, System.currentTimeMillis())) return c
        if (!lock.tryLock(LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) return null
        try {
            val now = System.currentTimeMillis()
            val c2 = cur
            if (usable(c2, now)) return c2
            c2?.fail(); cur = null
            if (closed || now < downUntil) return null
            return try { open().also { cur = it } } catch (_: Exception) { noteFailure(); null }
        } finally { lock.unlock() }
    }

    /** Catat kegagalan sekali per masa jeda (puluhan query yang gagal bersamaan tidak melipatgandakan jeda). */
    private fun noteFailure() {
        synchronized(this) {
            val now = System.currentTimeMillis()
            if (now < downUntil) return
            val n = fails.incrementAndGet()
            downUntil = now + if (strict) STRICT_BACKOFF else minOf(MAX_BACKOFF, BASE_BACKOFF shl minOf(n - 1, 5))
        }
    }

    private fun noteSuccess() { if (fails.get() != 0) { fails.set(0); downUntil = 0L } }

    /** Kirim satu query; null bila gagal (pemanggil memutuskan jatuh ke DNS biasa atau SERVFAIL pada mode ketat). */
    fun query(q: ByteArray): ByteArray? {
        if (closed || q.size < 12) return null
        if (System.currentTimeMillis() < downUntil) return null
        // Koneksi lama bisa sudah mati diam-diam (NAT): satu kali gagal di koneksi yang pernah sukses diulang di koneksi baru.
        for (attempt in 0..1) {
            val c = conn() ?: return null
            val reused = c.served > 0
            val p = Pending()
            var id = -1
            for (k in 0 until 8) { // cari ID bebas pada koneksi ini
                val t = nextId.incrementAndGet() and 0xFFFF
                if (c.pending.putIfAbsent(t, p) == null) { id = t; break }
            }
            if (id < 0) return null
            val framed = ByteArray(2 + q.size)
            framed[0] = (q.size shr 8).toByte(); framed[1] = q.size.toByte()
            System.arraycopy(q, 0, framed, 2, q.size)
            framed[2] = (id shr 8).toByte(); framed[3] = id.toByte()
            try {
                synchronized(c.output) { c.output.write(framed); c.output.flush() }
                c.lastUsed = System.currentTimeMillis()
            } catch (_: Exception) { c.pending.remove(id); c.fail(); if (reused) continue; noteFailure(); return null }
            val got = p.latch.await(ANSWER_MS, TimeUnit.MILLISECONDS)
            c.pending.remove(id)
            val r = p.reply
            if (r != null) {
                r[0] = q[0]; r[1] = q[1]
                c.served++; c.lastUsed = System.currentTimeMillis(); noteSuccess()
                return r
            }
            if (!got) c.fail() // server menggantung: buang koneksi agar query berikutnya membuka yang baru
            if (reused) continue
            noteFailure(); return null
        }
        noteFailure()
        return null
    }

    /** Jaringan berganti: koneksi lama pasti mati dan status gagal tidak relevan lagi. */
    fun reset() {
        fails.set(0); downUntil = 0L
        val c = cur; cur = null
        c?.fail()
    }

    fun close() { closed = true; reset() }
}
