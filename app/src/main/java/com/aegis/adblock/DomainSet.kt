package com.aegis.adblock

/**
 * Himpunan domain kompak: tiap domain disimpan sebagai hash 64-bit di LongArray terurut, dicari dengan
 * binary search. ~8 byte/domain (HashSet<String> ~100+ byte/domain), jadi 500 ribu domain hanya ~4 MB
 * dan tidak membebani GC. Peluang tabrakan hash untuk 500 ribu entri ~1e-8 (diabaikan).
 */
class DomainSet private constructor(private val h: LongArray) {
    val size: Int get() = h.size

    fun contains(domain: String): Boolean = h.isNotEmpty() && java.util.Arrays.binarySearch(h, hash(domain)) >= 0

    companion object {
        val EMPTY = DomainSet(LongArray(0))

        /** FNV-1a 64-bit + finalizer (murmur3 fmix) supaya bit tersebar merata. */
        fun hash(s: String): Long {
            var x = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in s) { x = (x xor c.code.toLong()) * 0x100000001b3L }
            x = x xor (x ushr 33); x *= -0xae502812aa7333L
            x = x xor (x ushr 33); x *= -0x3b314601e57a13adL
            return x xor (x ushr 33)
        }

        /** Bangun dari baris teks (sudah dinormalisasi lowercase). Duplikat otomatis dibuang. */
        fun build(lines: Sequence<String>): DomainSet {
            var arr = LongArray(1 shl 16); var n = 0
            for (l in lines) {
                if (n == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[n++] = hash(l)
            }
            if (n == 0) return EMPTY
            arr = arr.copyOf(n); arr.sort()
            var u = 1
            for (i in 1 until n) if (arr[i] != arr[u - 1]) arr[u++] = arr[i]
            return DomainSet(if (u == n) arr else arr.copyOf(u))
        }
    }
}
