package com.aegis.adblock

import android.content.*

/**
 * Runs once after device reboot. Only restarts the VPN protection if the
 * user has explicitly turned on "Nyalakan otomatis setelah restart HP" in
 * the app (see Prefs.AUTOSTART in MainActivity). If the user has that
 * toggle off, or force-stopped the app, nothing here brings it back —
 * Android also won't deliver BOOT_COMPLETED to a force-stopped app anyway.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Updater.schedule(c)
        val autostart = c.getSharedPreferences("aegis_prefs", Context.MODE_PRIVATE)
            .getBoolean(Prefs.AUTOSTART, false)
        if (autostart) {
            val svc = Intent(c, AdBlockVpnService::class.java)
            try { c.startForegroundService(svc) } catch (_: Exception) { /* VPN consent may be required again */ }
        }
    }
}

object Prefs {
    const val AUTOSTART = "autostart_after_reboot"
}
