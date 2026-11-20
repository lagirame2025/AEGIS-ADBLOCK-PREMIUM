package com.aegis.adblock

import android.content.Context
import androidx.work.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Repo GitHub tempat pipeline menyimpan blocklist.txt & cosmetic.txt. */
const val BASE_URL = "https://raw.githubusercontent.com/lagirame2025/AEGIS-ADBLOCK-PREMIUM/main/"

class Updater(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result = if (fetch(applicationContext)) Result.success() else Result.retry()

    companion object {
        private fun download(ctx: Context, name: String, minSize: Long): Boolean = try {
            val con = URL(BASE_URL + name).openConnection() as HttpURLConnection
            con.connectTimeout = 15000; con.readTimeout = 30000
            if (con.responseCode != 200) false else {
                val tmp = File(ctx.filesDir, "$name.tmp")
                con.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                if (tmp.length() < minSize) false else tmp.renameTo(File(ctx.filesDir, name))
            }
        } catch (e: Exception) { false }

        fun fetch(ctx: Context): Boolean {
            val a = download(ctx, "blocklist.txt", 1000)
            val b = download(ctx, "cosmetic.txt", 100)
            if (a) Blocklist.load(ctx)
            return a || b
        }

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<Updater>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("blocklist", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
