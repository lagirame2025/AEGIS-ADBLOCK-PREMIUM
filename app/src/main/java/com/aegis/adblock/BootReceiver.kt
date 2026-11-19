package com.aegis.adblock

import android.content.*

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Updater.schedule(c) }
}
