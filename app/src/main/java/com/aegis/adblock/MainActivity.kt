package com.aegis.adblock

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.*
import android.view.Gravity
import android.widget.*
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

class MainActivity : Activity() {
    private lateinit var stats: TextView
    private lateinit var toggle: Button
    private lateinit var prefs: android.content.SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(48, 48, 48, 48) }
        stats = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        val upd = Button(this).apply {
            text = "Perbarui blocklist"
            setOnClickListener {
                Thread {
                    val ok = Updater.fetch(this@MainActivity)
                    runOnUiThread { Toast.makeText(this@MainActivity, if (ok) "Blocklist diperbarui" else "Gagal memperbarui", Toast.LENGTH_SHORT).show() }
                }.start()
            }
        }
        val br = Button(this).apply { text = "Buka browser anti-iklan (YouTube dll)"; setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) } }
        val pd = Button(this).apply { text = "Private DNS (tanpa VPN, WiFi & 5G)"; setOnClickListener { privateDns() } }

        prefs = getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE)
        val autostartRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val autostartSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(Prefs.AUTOSTART, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.AUTOSTART, checked).apply()
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "Proteksi akan otomatis menyala setelah HP di-restart. Kamu bisa matikan opsi ini kapan saja."
                    else "Autostart setelah restart dimatikan.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        autostartRow.addView(TextView(this).apply { text = "Nyalakan otomatis setelah restart HP  " })
        autostartRow.addView(autostartSwitch)

        val battery = Button(this).apply {
            text = "Minta izin bebas optimisasi baterai"
            setOnClickListener { requestBatteryExemption() }
        }

        root.addView(stats); root.addView(toggle); root.addView(br); root.addView(pd)
        root.addView(autostartRow); root.addView(battery); root.addView(upd)
        setContentView(root)
        Blocklist.load(this); Updater.schedule(this)
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        toggle.text = if (AdBlockVpnService.running) "Matikan proteksi" else "Aktifkan proteksi"
        stats.text = "Status: ${if (AdBlockVpnService.running) "AKTIF" else "mati"}\nDomain di blocklist: ${Blocklist.size()}\n" +
            "Query DNS: ${AdBlockVpnService.totalCount.get()}\nIklan diblokir: ${AdBlockVpnService.blockedCount.get()}"
    }
    private fun onToggle() {
        if (AdBlockVpnService.running) { startService(Intent(this, AdBlockVpnService::class.java).setAction("STOP")); return }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else startVpn()
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) { if (r == 1 && c == RESULT_OK) startVpn() }
    private fun privateDns() {
        val host = "dns.adguard-dns.com"
        AlertDialog.Builder(this).setTitle("Private DNS (Android 9+)")
            .setMessage("1. Tekan tombol di bawah (hostname disalin).\n2. Buka Jaringan & internet > Private DNS (lokasi bisa beda tiap merek).\n3. Pilih 'Nama host penyedia Private DNS' lalu tempel: $host\n\nBerlaku di WiFi dan data seluler, tanpa VPN. Semua query DNS dikirim ke AdGuard DNS.")
            .setPositiveButton("Salin & buka Pengaturan") { _, _ ->
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("dns", host))
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }.setNegativeButton("Batal", null).show()
    }
    private fun startVpn() { startService(Intent(this, AdBlockVpnService::class.java)) }

    /**
     * Shows Android's OWN system dialog asking the user to exempt this app
     * from battery optimization. This is the standard, Play-Store-compliant
     * way to keep a long-running VPN service from being killed by OEM
     * battery managers — the user sees exactly what's being requested and
     * can deny it or revoke it later from Settings > Battery.
     */
    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Sudah dibebaskan dari optimisasi baterai", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            // Some OEM ROMs block this intent; fall back to the general battery settings screen.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
