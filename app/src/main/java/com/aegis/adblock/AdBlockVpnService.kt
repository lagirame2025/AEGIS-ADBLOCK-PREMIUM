package com.aegis.adblock

import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AdBlockVpnService : VpnService() {
    companion object {
        @Volatile var running = false
        val blockedCount = AtomicInteger(); val totalCount = AtomicInteger()
        const val FAKE_DNS = "10.111.0.1"
        const val UPSTREAM = "1.1.1.1"
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
        Thread { loop(pfd) }.start()
        return START_STICKY
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

    private fun stop() { running = false; tun?.close(); tun = null; stopSelf() }
    override fun onRevoke() { stop() }
    override fun onDestroy() { running = false; tun?.close(); super.onDestroy() }
}
