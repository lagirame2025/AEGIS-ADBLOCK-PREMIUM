package com.aegis.adblock

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Dialog pengaturan server DoT pribadi (mode hibrida). Dibangun lewat kode, tanpa XML/appcompat, supaya bisa
 * dipanggil dari mana saja, mis. dari tombol di MainActivity:
 *
 *     DotSettings.show(this)
 *
 * Menyimpan ke SharedPreferences "aegis_prefs" dengan kunci yang dibaca AdBlockVpnService.loadDot():
 * dot_host, dot_ip, dot_strict. Kosongkan host dan IP untuk mematikan mode hibrida.
 */
object DotSettings {
    private fun isIpv4(s: String): Boolean {
        val p = s.split(".")
        return p.size == 4 && p.all { (it.toIntOrNull() ?: -1) in 0..255 }
    }

    fun show(a: Activity) {
        val sp = a.getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE)
        val pad = (16 * a.resources.displayMetrics.density).toInt()

        val note = TextView(a).apply {
            text = "Query yang lolos filter dikirim lewat DNS-over-TLS ke server Anda. " +
                "IP harus IPv4 (bukan nama). Nama host harus sama dengan nama di sertifikat server."
        }
        val host = EditText(a).apply {
            hint = "Nama host (mis. dot.contoh.com)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(sp.getString(Prefs.DOT_HOST, ""))
        }
        val ip = EditText(a).apply {
            hint = "IPv4 server (mis. 203.0.113.10)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(sp.getString(Prefs.DOT_IP, ""))
        }
        val strict = CheckBox(a).apply {
            text = "Mode ketat: jangan pernah jatuh ke DNS biasa (internet bisa mati bila server DoT mati)"
            isChecked = sp.getBoolean(Prefs.DOT_STRICT, false)
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(note); addView(host); addView(ip); addView(strict)
        }

        AlertDialog.Builder(a)
            .setTitle("Server DoT pribadi")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val h = host.text.toString().trim(); val i = ip.text.toString().trim()
                when {
                    h.isEmpty() && i.isEmpty() -> {
                        sp.edit().remove(Prefs.DOT_HOST).remove(Prefs.DOT_IP).putBoolean(Prefs.DOT_STRICT, false).apply()
                        done(a, "Mode hibrida dimatikan.")
                    }
                    h.isEmpty() || !isIpv4(i) ->
                        Toast.makeText(a, "Isi nama host dan IPv4 yang valid. Tidak disimpan.", Toast.LENGTH_LONG).show()
                    else -> {
                        sp.edit().putString(Prefs.DOT_HOST, h).putString(Prefs.DOT_IP, i)
                            .putBoolean(Prefs.DOT_STRICT, strict.isChecked).apply()
                        done(a, "Tersimpan.")
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Konfigurasi dibaca saat VPN dinyalakan, jadi VPN yang sedang jalan perlu dimatikan lalu dinyalakan lagi. */
    private fun done(a: Activity, msg: String) {
        val extra = if (AdBlockVpnService.running) " Matikan lalu nyalakan lagi proteksi agar berlaku." else ""
        Toast.makeText(a, msg + extra, Toast.LENGTH_LONG).show()
    }
}
