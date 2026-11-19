package com.aegis.adblock

object Dns {
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

    /** Jawab A=0.0.0.0, AAAA=::, tipe lain: kosong (NOERROR). */
    fun blockedReply(q: ByteArray, end: Int, qtype: Int): ByteArray {
        val head = q.copyOf(end)
        head[2] = 0x81.toByte(); head[3] = 0x80.toByte()
        for (k in 6..11) head[k] = 0
        val rdlen = when (qtype) { 1 -> 4; 28 -> 16; else -> return head }
        head[7] = 1
        val ans = ByteArray(12 + rdlen)
        ans[0] = 0xC0.toByte(); ans[1] = 0x0C
        ans[3] = qtype.toByte(); ans[5] = 1; ans[9] = 60; ans[11] = rdlen.toByte()
        return head + ans
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
        r[21] = 53
        r[22] = orig[ihl]; r[23] = orig[ihl + 1]
        val ul = 8 + payload.size; r[24] = (ul shr 8).toByte(); r[25] = ul.toByte()
        System.arraycopy(payload, 0, r, 28, payload.size)
        return r
    }
}
