package com.aegis.adblock

import android.content.Context

/**
 * Pembelajar salah-blokir. Dua jalur:
 *  1. Pasif/otomatis (sempit & aman): domain yang diblokir HANYA oleh heuristik (tidak ada di daftar mana pun),
 *     berlabel "lemah" (telemetry/beacon/pixel, paling sering salah sasaran), diminta berulang kali dalam waktu
 *     singkat oleh SATU aplikasi non-browser (tanda aplikasi bergantung padanya) -> izin SEMENTARA 72 jam,
 *     terlihat di "Aturan sendiri", bisa dihapus kapan saja, dan kedaluwarsa sendiri sehingga dievaluasi ulang.
 *  2. Aktif: tombol "Ada yang rusak?" memilih kandidat dari query yang diblokir beberapa detik terakhir.
 * Domain yang ada di daftar blokir TIDAK PERNAH diizinkan otomatis.
 */
object Learner {
    private val WEAK = Regex("(telemetry|beacon|pixel)\\d{0,3}")
    private val BROWSERS = listOf("com.android.chrome", "org.mozilla.", "com.brave.", "com.opera.", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.duckduckgo.", "com.vivaldi.", "com.kiwibrowser.", "com.aegis.adblock")
    private const val BURST = 4
    private const val WINDOW_MS = 20_000L
    const val AUTO_HOURS = 72L

    fun onBlocked(ctx: Context, name: String, heurOnly: Boolean, pkg: String?) {
        Stats.addEvent(Stats.Event(System.currentTimeMillis(), name.lowercase().trimEnd('.'), heurOnly, pkg))
        if (!heurOnly || pkg == null) return
        if (!ctx.getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE).getBoolean(Prefs.AUTO, true)) return
        if (BROWSERS.any { pkg == it || (it.endsWith(".") && pkg.startsWith(it)) }) return
        val label = Blocklist.heuristicLabel(name) ?: return
        if (!WEAK.matches(label)) return
        if (Stats.burst(name, pkg, WINDOW_MS) < BURST) return
        Blocklist.addTemp(ctx, name, AUTO_HOURS, auto = true)
    }
}
