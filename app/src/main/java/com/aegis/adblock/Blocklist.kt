package com.aegis.adblock

import android.content.Context
import java.io.File

object Blocklist {
    @Volatile private var blocked: Set<String> = emptySet()
    @Volatile private var allowed: Set<String> = emptySet()

    private fun parse(lines: Sequence<String>) = lines.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()

    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "blocklist.txt")
        val b = if (f.exists() && f.length() > 0) f.useLines { parse(it) }
                else ctx.assets.open("seed.txt").bufferedReader().useLines { parse(it) }
        allowed = ctx.assets.open("allow.txt").bufferedReader().useLines { parse(it) }
        blocked = b
    }

    fun size() = blocked.size

    /** Cocokkan domain dan semua parent-nya (sub.ads.com -> ads.com). Allowlist menang. */
    fun isBlocked(name: String): Boolean {
        var d = name.lowercase().trimEnd('.')
        var hit = false
        while (true) {
            if (d in allowed) return false
            if (d in blocked) hit = true
            val i = d.indexOf('.')
            if (i < 0) break
            d = d.substring(i + 1)
        }
        return hit
    }
}
