package com.aegis.adblock

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
        val logBtn = Button(this).apply { text = "Log diblokir (cek situs/aplikasi rusak)"; setOnClickListener { showLog() } }
        val rulesBtn = Button(this).apply { text = "Aturan sendiri (blokir / izinkan domain)"; setOnClickListener { showRules() } }

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

        // Mode ketat: heuristik nama iklan + tangkap DNS hardcoded + blokir DoH. Matikan bila ada situs/aplikasi rusak.
        val strictRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val strictSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(Prefs.STRICT, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.STRICT, checked).apply()
                Blocklist.strict = checked
                Toast.makeText(
                    this@MainActivity,
                    "Mode ketat ${if (checked) "AKTIF" else "mati"}. Matikan lalu aktifkan lagi proteksi agar penuh berlaku.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        strictRow.addView(TextView(this).apply { text = "Mode ketat (heuristik iklan + blokir DoH)  " })
        strictRow.addView(strictSwitch)

        val battery = Button(this).apply {
            text = "Minta izin bebas optimisasi baterai"
            setOnClickListener { requestBatteryExemption() }
        }

        root.addView(stats); root.addView(toggle); root.addView(br); root.addView(pd)
        root.addView(strictRow); root.addView(autostartRow)
        val bypassBtn = Button(this).apply { text = "Aplikasi dikecualikan (bypass VPN)"; setOnClickListener { showBypass() } }
        val fixBtn = Button(this).apply { text = "Ada yang rusak? Perbaiki cepat"; setOnClickListener { quickFix() } }
        val autoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        autoRow.addView(TextView(this).apply { text = "Izin otomatis sementara untuk tebakan keliru  " })
        autoRow.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(Prefs.AUTO, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.AUTO, on).apply() }
        })
        root.addView(fixBtn); root.addView(autoRow)
        root.addView(bypassBtn); root.addView(logBtn); root.addView(rulesBtn); root.addView(battery); root.addView(upd)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        Blocklist.load(this); Updater.schedule(this)

        // Android 13+: tanpa izin ini notifikasi status (dan tombol "Matikan") tidak tampil.
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
        }
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        toggle.text = if (AdBlockVpnService.running) "Matikan proteksi" else "Aktifkan proteksi"
        stats.text = "Status: ${if (AdBlockVpnService.running) "AKTIF" else "mati"}\nDomain di blocklist: ${Blocklist.size()}\n" +
            "Query DNS: ${AdBlockVpnService.totalCount.get()}\nIklan diblokir: ${AdBlockVpnService.blockedCount.get()}\n" +
            "Dari cache: ${AdBlockVpnService.cacheHits.get()} (basi: ${AdBlockVpnService.staleServed.get()})  Hedged: ${AdBlockVpnService.hedged.get()}\n" +
            "Pembaruan terakhir: ${lastUpdateText()}"
    }
    private fun lastUpdateText(): String {
        val t = Updater.lastOk(this)
        if (t == 0L) return "belum pernah"
        val m = (System.currentTimeMillis() - t) / 60000
        return if (m < 1) "baru saja" else if (m < 60) "$m menit lalu" else if (m < 1440) "${m / 60} jam lalu" else "${m / 1440} hari lalu"
    }
    private fun onToggle() {
        if (AdBlockVpnService.running) { startService(Intent(this, AdBlockVpnService::class.java).setAction("STOP")); return }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else startVpn()
    }
    override fun onActivityResult(r: Int, c: Int, d: Intent?) { if (r == 1 && c == RESULT_OK) startVpn() }

    /** Daftar domain yang baru saja diblokir. Ketuk satu untuk mengizinkannya bila ada yang rusak. */
    private fun showLog() {
        val items = Stats.recent().toTypedArray()
        if (items.isEmpty()) { Toast.makeText(this, "Belum ada domain yang diblokir", Toast.LENGTH_SHORT).show(); return }
        val labels = items.map {
            val app = Attribution.label(this, Stats.pkgOf(it))
            "$it  (×${Stats.count(it)})" + (if (app.isNotEmpty()) "  — $app" else "") + (if (Stats.isHeur(it)) "  [tebakan]" else "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Terakhir diblokir — ketuk untuk IZINKAN")
            .setItems(labels) { _, which -> confirmAllow(items[which]) }
            .setNegativeButton("Tutup", null).show()
    }

    private fun confirmAllow(domain: String) {
        AlertDialog.Builder(this).setMessage("Izinkan $domain (dan subdomainnya)? Lakukan hanya bila aplikasi/situs yang kamu pakai jadi rusak.")
            .setPositiveButton("Izinkan") { _, _ ->
                val d = Blocklist.addCustom(this, true, domain)
                Toast.makeText(this, if (d != null) "$d diizinkan" else "Domain tidak valid", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Batal", null).show()
    }

    private fun showRules() {
        val input = EditText(this).apply { hint = "contoh: ads.situs.com"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("Aturan sendiri").setView(input)
            .setPositiveButton("Blokir") { _, _ -> addRule(false, input.text.toString()) }
            .setNeutralButton("Izinkan") { _, _ -> addRule(true, input.text.toString()) }
            .setNegativeButton("Lihat daftar") { _, _ -> showCustomList() }.show()
    }

    private fun addRule(allow: Boolean, raw: String) {
        val d = Blocklist.addCustom(this, allow, raw)
        Toast.makeText(this, if (d == null) "Domain tidak valid" else if (allow) "$d diizinkan" else "$d diblokir", Toast.LENGTH_SHORT).show()
    }

    /**
     * "Ada yang rusak?": kumpulkan domain yang diblokir 2 menit terakhir (tebakan heuristik dicentang lebih dulu,
     * lengkap dengan nama aplikasinya bila Android 10+), lalu izinkan yang dipilih 24 jam atau permanen.
     */
    private fun quickFix() {
        val ev = Stats.eventsSince(120_000L).distinctBy { it.name }.sortedByDescending { it.heur }
        if (ev.isEmpty()) { Toast.makeText(this, "Tidak ada domain diblokir dalam 2 menit terakhir. Coba buka aplikasi/situs yang rusak dulu, lalu ketuk ini lagi.", Toast.LENGTH_LONG).show(); return }
        val checked = BooleanArray(ev.size) { ev[it].heur }
        val labels = ev.map {
            val app = Attribution.label(this, it.pkg)
            it.name + (if (app.isNotEmpty()) "  — $app" else "") + (if (it.heur) "  [tebakan]" else "")
        }.toTypedArray()
        fun pick() = ev.filterIndexed { i, _ -> checked[i] }.map { it.name }
        AlertDialog.Builder(this).setTitle("Izinkan domain yang diblokir barusan?")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Izinkan 24 jam") { _, _ ->
                val n = pick().count { Blocklist.addTemp(this, it, 24, auto = false) != null }
                Toast.makeText(this, "$n domain diizinkan 24 jam", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Permanen") { _, _ ->
                val n = pick().count { Blocklist.addCustom(this, true, it) != null }
                Toast.makeText(this, "$n domain diizinkan permanen", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun showCustomList() {
        val allowList = Blocklist.customList(this, true)
        val blockList = Blocklist.customList(this, false)
        val tempList = Blocklist.tempList()
        val labels = (allowList.map { "✔ izinkan: $it" } + blockList.map { "✖ blokir: $it" } +
            tempList.map { "⏱ ${if (it.third) "otomatis" else "sementara"} (${it.second} jam): ${it.first}" }).toTypedArray()
        if (labels.isEmpty()) { Toast.makeText(this, "Belum ada aturan sendiri", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Aturan sendiri — ketuk untuk HAPUS")
            .setItems(labels) { _, which ->
                if (which < allowList.size) Blocklist.removeCustom(this, true, allowList[which])
                else if (which < allowList.size + blockList.size) Blocklist.removeCustom(this, false, blockList[which - allowList.size])
                else Blocklist.removeTemp(this, tempList[which - allowList.size - blockList.size].first)
                Toast.makeText(this, "Aturan dihapus", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Tutup", null).show()
    }

    /** Pilih aplikasi yang tidak ikut difilter (berlaku saat proteksi dinyalakan ulang). */
    private fun showBypass() {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) { Toast.makeText(this, "Tidak ada aplikasi ditemukan", Toast.LENGTH_SHORT).show(); return }
        val chosen = HashSet(prefs.getStringSet(Prefs.BYPASS, emptySet()) ?: emptySet())
        AlertDialog.Builder(this).setTitle("Centang = TIDAK difilter")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), BooleanArray(apps.size) { apps[it].first in chosen }) { _, i, on ->
                if (on) chosen.add(apps[i].first) else chosen.remove(apps[i].first)
            }
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putStringSet(Prefs.BYPASS, chosen).apply()
                Toast.makeText(this, "Tersimpan. Matikan lalu nyalakan proteksi agar berlaku.", Toast.LENGTH_LONG).show()
            }.setNegativeButton("Batal", null).show()
    }

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
