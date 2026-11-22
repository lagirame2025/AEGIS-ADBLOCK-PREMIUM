package com.aegis.adblock

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Server DNS-over-TCP minimal (RFC 7766) yang hidup di dalam tunnel. Dipakai klien yang jatuh ke TCP
 * (balasan terpotong/TC, `dig +tcp`, resolver tertentu). Tunnel adalah jalur andal tanpa kehilangan paket,
 * jadi cukup state machine sederhana: handshake, segmen berurutan, FIN. Tanpa retransmisi/congestion control.
 *
 * Hanya port 53 yang dilayani; SYN ke 853 (DoT)/443 (DoH) tetap di-RST oleh [Dns.tcpReset].
 */
class TcpDns(
    private val answer: (ByteArray) -> ByteArray?,
    private val submit: (Runnable) -> Unit
) {
    private class Conn(val cip: ByteArray, val sip: ByteArray, val cport: Int, val sport: Int, val isn: Long) {
        @Volatile var rcvNxt = 0L
        @Volatile var sndNxt = 0L
        @Volatile var last = System.currentTimeMillis()
        @Volatile var closed = false
        @Volatile var finRecv = false
        val pending = AtomicInteger()
        var buf = ByteArray(0)
    }

    private val conns = ConcurrentHashMap<String, Conn>()
    private val rnd = SecureRandom()

    companion object {
        private const val M = 0xFFFFFFFFL
        private const val MSS = 1400
        private const val FIN = 0x01; private const val SYN = 0x02; private const val RST = 0x04
        private const val PSH = 0x08; private const val ACK = 0x10
    }

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
    private fun u32(b: ByteArray, p: Int): Long = (u16(b, p).toLong() shl 16) or u16(b, p + 2).toLong()
    private fun wr32(b: ByteArray, p: Int, v: Long) {
        b[p] = (v shr 24).toByte(); b[p + 1] = (v shr 16).toByte(); b[p + 2] = (v shr 8).toByte(); b[p + 3] = v.toByte()
    }

    /** Checksum satu's-complement yang benar untuk panjang ganjil. */
    private fun sum(b: ByteArray, off: Int, len: Int, init: Long): Long {
        var s = init; var i = 0
        while (i + 1 < len) { s += ((b[off + i].toInt() and 0xFF) shl 8) or (b[off + i + 1].toInt() and 0xFF); i += 2 }
        if (i < len) s += (b[off + i].toInt() and 0xFF) shl 8
        return s
    }
    private fun fold(s0: Long): Int {
        var s = s0
        while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    /** Bangun paket IPv4+TCP lengkap dengan checksum IP dan TCP. */
    private fun seg(src: ByteArray, dst: ByteArray, sp: Int, dp: Int, seq: Long, ack: Long, flags: Int,
                    data: ByteArray?, off: Int, len: Int, mss: Boolean): ByteArray {
        val th = if (mss) 24 else 20
        val tl = th + len; val total = 20 + tl
        val r = ByteArray(total)
        r[0] = 0x45; r[2] = (total shr 8).toByte(); r[3] = total.toByte(); r[6] = 0x40; r[8] = 64; r[9] = 6
        System.arraycopy(src, 0, r, 12, 4); System.arraycopy(dst, 0, r, 16, 4)
        val ic = fold(sum(r, 0, 20, 0)); r[10] = (ic shr 8).toByte(); r[11] = ic.toByte()
        val t = 20
        r[t] = (sp shr 8).toByte(); r[t + 1] = sp.toByte(); r[t + 2] = (dp shr 8).toByte(); r[t + 3] = dp.toByte()
        wr32(r, t + 4, seq and M); wr32(r, t + 8, ack and M)
        r[t + 12] = ((th / 4) shl 4).toByte(); r[t + 13] = flags.toByte(); r[t + 14] = 0xFF.toByte(); r[t + 15] = 0xFF.toByte()
        if (mss) { r[t + 20] = 2; r[t + 21] = 4; r[t + 22] = (MSS shr 8).toByte(); r[t + 23] = (MSS and 0xFF).toByte() }
        if (len > 0) System.arraycopy(data!!, off, r, t + th, len)
        var s = sum(r, 12, 8, 0L)            // pseudo-header: IP sumber + tujuan
        s += 6L + tl                          // protokol + panjang TCP
        s = sum(r, t, tl, s)
        val c = fold(s); r[t + 16] = (c shr 8).toByte(); r[t + 17] = c.toByte()
        return r
    }

    private fun sweep() {
        val old = System.currentTimeMillis() - 30_000L
        for ((k, c) in conns) if (c.last < old) { c.closed = true; conns.remove(k) }
    }

    /** Proses satu paket TCP ber-tujuan port 53 (dipanggil dari loop tunnel). */
    fun onSegment(pkt: ByteArray, n: Int, ihl: Int, write: (ByteArray) -> Unit) {
        if (n < ihl + 20) return
        val total = minOf(n, u16(pkt, 2))
        val dataOff = ((pkt[ihl + 12].toInt() and 0xF0) shr 4) * 4
        if (dataOff < 20 || total < ihl + dataOff) return
        val flags = pkt[ihl + 13].toInt() and 0xFF
        val seq = u32(pkt, ihl + 4); val ack = u32(pkt, ihl + 8)
        val plen = total - ihl - dataOff
        val cip = pkt.copyOfRange(12, 16); val sip = pkt.copyOfRange(16, 20)
        val cport = u16(pkt, ihl); val sport = u16(pkt, ihl + 2)
        val key = "${cip.joinToString(".")}:$cport>${sip.joinToString(".")}:$sport"
        val rst = flags and RST != 0; val syn = flags and SYN != 0
        val fin = flags and FIN != 0; val hasAck = flags and ACK != 0

        if (rst) { conns.remove(key)?.closed = true; return }

        if (syn && !hasAck) {
            var c = conns[key]
            if (c == null) {
                sweep()
                if (conns.size >= 64) { write(seg(sip, cip, sport, cport, 0L, (seq + 1) and M, RST or ACK, null, 0, 0, false)); return }
                c = Conn(cip, sip, cport, sport, rnd.nextInt().toLong() and M)
                c.rcvNxt = (seq + 1) and M; c.sndNxt = (c.isn + 1) and M
                conns[key] = c
            }
            write(seg(sip, cip, sport, cport, c.isn, c.rcvNxt, SYN or ACK, null, 0, 0, true)) // SYN retransmit: ISN sama
            return
        }

        val c = conns[key]
        if (c == null) {
            if (hasAck && !syn && !fin && plen == 0) return // ACK nyasar (mis. untuk FIN kita): abaikan
            write(if (hasAck) seg(sip, cip, sport, cport, ack, 0L, RST, null, 0, 0, false)
                  else seg(sip, cip, sport, cport, 0L, (seq + plen + (if (fin) 1 else 0)) and M, RST or ACK, null, 0, 0, false))
            return
        }

        synchronized(c) {
            if (c.closed) return
            c.last = System.currentTimeMillis()
            if (plen > 0) {
                if (seq != c.rcvNxt) { // duplikat/di luar urutan: minta yang diharapkan
                    write(seg(sip, cip, sport, cport, c.sndNxt, c.rcvNxt, ACK, null, 0, 0, false)); return
                }
                c.rcvNxt = (c.rcvNxt + plen) and M
                c.buf += pkt.copyOfRange(ihl + dataOff, ihl + dataOff + plen)
                write(seg(sip, cip, sport, cport, c.sndNxt, c.rcvNxt, ACK, null, 0, 0, false))
                if (c.buf.size > 70_000) { c.closed = true; conns.remove(key); write(seg(sip, cip, sport, cport, c.sndNxt, c.rcvNxt, RST or ACK, null, 0, 0, false)); return }
                while (c.buf.size >= 2) {
                    val ml = u16(c.buf, 0)
                    if (ml == 0) { c.buf = ByteArray(0); break } // pesan kosong tidak valid
                    if (c.buf.size < 2 + ml) break               // belum lengkap: tunggu segmen berikutnya
                    val msg = c.buf.copyOfRange(2, 2 + ml)
                    c.buf = c.buf.copyOfRange(2 + ml, c.buf.size)
                    c.pending.incrementAndGet()
                    submit(Runnable {
                        try { answer(msg)?.let { reply(c, it, write) } }
                        catch (_: Exception) { }
                        finally { c.pending.decrementAndGet(); closeIfDone(c, key, write) }
                    })
                }
            }
            if (fin && ((seq + plen) and M) == c.rcvNxt && !c.finRecv) {
                c.finRecv = true; c.rcvNxt = (c.rcvNxt + 1) and M
                write(seg(sip, cip, sport, cport, c.sndNxt, c.rcvNxt, ACK, null, 0, 0, false))
                closeIfDone(c, key, write)
            }
        }
    }

    /** Kirim balasan DNS berbingkai (2 byte panjang), dipecah per MSS bila besar. */
    private fun reply(c: Conn, msg: ByteArray, write: (ByteArray) -> Unit) {
        val framed = ByteArray(2 + msg.size)
        framed[0] = (msg.size shr 8).toByte(); framed[1] = msg.size.toByte()
        System.arraycopy(msg, 0, framed, 2, msg.size)
        synchronized(c) {
            if (c.closed) return
            var off = 0
            while (off < framed.size) {
                val len = minOf(MSS, framed.size - off)
                val flags = if (off + len >= framed.size) PSH or ACK else ACK
                write(seg(c.sip, c.cip, c.sport, c.cport, c.sndNxt, c.rcvNxt, flags, framed, off, len, false))
                c.sndNxt = (c.sndNxt + len) and M; off += len
            }
            c.last = System.currentTimeMillis()
        }
    }

    /** Setelah klien menutup (FIN) dan semua jawaban terkirim, tutup sisi kita juga. */
    private fun closeIfDone(c: Conn, key: String, write: (ByteArray) -> Unit) {
        synchronized(c) {
            if (c.closed || !c.finRecv || c.pending.get() > 0) return
            c.closed = true
            write(seg(c.sip, c.cip, c.sport, c.cport, c.sndNxt, c.rcvNxt, FIN or ACK, null, 0, 0, false))
            c.sndNxt = (c.sndNxt + 1) and M
            conns.remove(key)
        }
    }
}
