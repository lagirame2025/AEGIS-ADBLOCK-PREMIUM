package com.aegis.adblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.VpnService
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
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
 */
class AdBlockVpnService : VpnService() {
    companion object {
        @Volatile var running = false
        val blockedCount = AtomicInteger(); val totalCount = AtomicInteger()
        const val FAKE_DNS = "10.111.0.1"
        const val UPSTREAM = "1.1.1.1"
        private const val CHANNEL_ID = "aegis_vpn_status"
        private const val NOTIF_ID = 42
    }
    private var tun: ParcelFileDescriptor? = null
    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "STOP") { stop(); return START_NOT_STICKY }
        if (running) return START_STICKY
        Blocklist.load(this)
        val pfd = Builder().setSession("Aegis AdBlock").addAddress("10.111.0.2", 32)
            .addDnsServer(FAKE_DNS).addRoute(FAKE_DNS, 32).setMtu(1500).establish() ?: return START_NOT_STICKY
        tun = pfd; running = true
        startForeground(NOTIF_ID, buildNotification())
        Thread { loop(pfd) }.start()
        return START_STICKY
    }

    /** Ongoing, dismiss-proof-by-swipe notification — but it carries its own
     * "Matikan" action so the user is always one tap away from stopping the
     * service, exactly as Android's foreground-service guidelines require. */
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
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
        return Notification.Builder(this, CHANNEL_ID)
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
                val pkt = buf.copyOf(n)
                if ((pkt[0].toInt() shr 4) != 4 || pkt[9].toInt() != 17) continue
                val ihl = (pkt[0].toInt() and 0xF) * 4
                val dport = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
                if (dport != 53) continue
                pool.execute { handle(pkt, ihl, out) }
            }
        } catch (_: Exception) { }
    }

    private fun handle(pkt: ByteArray, ihl: Int, out: FileOutputStream) {
        try {
            val dns = pkt.copyOfRange(ihl + 8, pkt.size)
            val q = Dns.question(dns) ?: return
            totalCount.incrementAndGet()
            val reply = if (Blocklist.isBlocked(q.first)) {
                blockedCount.incrementAndGet(); Dns.blockedReply(dns, q.second, q.third)
            } else {
                DatagramSocket().use { s ->
                    protect(s); s.soTimeout = 3000
                    s.send(DatagramPacket(dns, dns.size, InetAddress.getByName(UPSTREAM), 53))
                    val r = ByteArray(4096); val p = DatagramPacket(r, r.size); s.receive(p); r.copyOf(p.length)
                }
            }
            val resp = Dns.wrapUdp(pkt, ihl, reply)
            synchronized(out) { out.write(resp) }
        } catch (_: Exception) { }
    }

    private fun stop() {
        running = false; tun?.close(); tun = null
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
    override fun onDestroy() { running = false; tun?.close(); super.onDestroy() }
}
