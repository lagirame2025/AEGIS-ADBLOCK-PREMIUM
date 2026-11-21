package com.aegis.adblock

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Mencari aplikasi pemilik sebuah query DNS (Android 10+ lewat getConnectionOwnerUid, yang hanya boleh dipanggil
 * aplikasi VPN aktif). Dipakai untuk log "domain - aplikasi" dan untuk pembelajaran allowlist per aplikasi.
 * Mahal (Binder), jadi hanya dipanggil untuk query yang diblokir, bukan semua query.
 */
object Attribution {
    private fun port(b: ByteArray, p: Int) = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    fun pkgFor(ctx: Context, pkt: ByteArray, ihl: Int, proto: Int): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val src = InetSocketAddress(InetAddress.getByAddress(pkt.copyOfRange(12, 16)), port(pkt, ihl))
            val dst = InetSocketAddress(InetAddress.getByAddress(pkt.copyOfRange(16, 20)), port(pkt, ihl + 2))
            val uid = cm.getConnectionOwnerUid(if (proto == 6) OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP, src, dst)
            if (uid < 0) null else ctx.packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Throwable) { null }
    }

    fun label(ctx: Context, pkg: String?): String {
        if (pkg == null) return ""
        return try {
            val pm = ctx.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (_: Throwable) { pkg }
    }
}
