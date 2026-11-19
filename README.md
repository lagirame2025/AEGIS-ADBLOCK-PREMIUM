# Aegis AdBlock
Pemblokir iklan berbasis DNS (VpnService lokal, tanpa root, tanpa lisensi/key). Android 5.0+ (minSdk 21), semua ABI.
1. Buat repo GitHub, upload folder ini. 2. Edit `BASE_URL` di `Updater.kt` (USER/REPO).
3. Actions > "Update blocklist" > Run (membuat blocklist.txt; lalu otomatis tiap 6 jam).
4. Actions > "Build APK" > unduh artifact APK. Aplikasi memperbarui blocklist tiap 12 jam.
Browser anti-iklan: cosmetic filter + scriptlet (json-prune, anti-popup) di level halaman, jalan sebelum skrip situs. Login Google: gunakan tombol ↗ (browser lain).
Batasan: tidak bisa memblokir iklan yang satu domain dengan konten (mis. YouTube); Private DNS/DoH bisa melewati filter.
