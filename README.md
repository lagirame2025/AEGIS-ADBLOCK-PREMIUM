# Aegis AdBlock
Pemblokir iklan berbasis DNS (VpnService lokal, tanpa root, tanpa lisensi/key). Android 5.0+ (minSdk 21), semua ABI.
1. Buat repo GitHub, upload folder ini. 2. Edit `BASE_URL` di `Updater.kt` (USER/REPO).
3. Actions > "Update blocklist" > Run (membuat blocklist.txt & cosmetic.txt; lalu otomatis tiap 6 jam).
4. Actions > "Build APK" > unduh artifact APK. Aplikasi memperbarui blocklist tiap 12 jam.

## Lapisan proteksi
**DNS (seluruh aplikasi)**
- Blocklist besar (StevenBlack, AdGuard DNS, Yoyo, AdAway, HaGeZi Pro + Pop-up Ads), subdomain redundan dibuang otomatis.
- **Mode ketat** (default aktif): heuristik nama iklan (`ads.situs.com`, `adserver.situs.co.id`, ...) hanya pada label *subdomain*, jadi `leads.com`/`adobe.com` aman.
- **CNAME uncloaking**: tracker yang menyamar sebagai subdomain situs sendiri ikut diblokir lewat target CNAME-nya.
- **Tangkap DNS hardcoded** (8.8.8.8, 1.1.1.1, 9.9.9.9, ...) + tolak DoH/DoT ke server publik dan matikan DoH Firefox (canary), agar filter tidak dilewati. Otomatis nonaktif bila kamu memakai Private DNS hostname sendiri.
- Cache DNS + upstream berlapis (AdGuard DNS -> Cloudflare -> Quad9) dengan SERVFAIL cepat; satu paket cacat tidak lagi mematikan VPN.
- **Log diblokir** + **Aturan sendiri** (blokir/izinkan domain) di panel: ketuk domain di log untuk mengizinkannya bila ada situs/aplikasi rusak.

**Browser anti-iklan**
- Cosmetic filter generik + **aturan per-situs** (EasyList + ABPindo untuk situs Indonesia), disuntik lewat constructable stylesheet (lolos CSP).
- Blokir request iklan: domain, pola URL, dan pola path iklan untuk request lintas-situs; balasan kosong sesuai jenis resource.
- Popup & redirect: `window.open` tanpa klik diblokir, popup lintas-situs diblokir (kecuali login/pembayaran sah), `intent://`/`market://` tanpa klik ditolak.
- Scriptlet: json-prune YouTube (player, feed, hasil cari), skip iklan video, hapus dialog "ad blocker dilarang", stub `googletag`/`adsbygoogle`, penghapus tembok "matikan adblock", sembunyikan iframe iklan ukuran standar.

Login Google: gunakan tombol ↗ (browser lain).
Batasan: DNS tidak bisa memblokir iklan yang satu domain dengan konten (mis. YouTube di app resmi); Private DNS/DoH hostname sendiri melewati filter VPN. Tidak ada perubahan workflow yang dibutuhkan: aturan per-situs disimpan di `cosmetic.txt` yang sama.

## Peningkatan v1.2 (logika inti)
- **Blocklist kompak**: domain disimpan sebagai hash 64-bit terurut (`DomainSet`), ~8 byte/domain, jadi daftar ratusan ribu domain tidak lagi menghabiskan puluhan MB heap.
- **Cache DNS lebih pintar**: sisa TTL dihitung benar, *negative caching* (NXDOMAIN/NODATA via SOA, RFC 2308), *serve-stale* 1 jam sambil refresh di latar belakang (RFC 8767), dan vonis CNAME-cloaking ikut di-cache.
- **Upstream adaptif + hedging**: urutan upstream mengikuti latensi & kegagalan (EWMA), permintaan kedua dikirim bila yang pertama lambat; DNS bawaan jaringan jadi cadangan terakhir; query identik digabung (*singleflight*); antrian paket dibatasi agar tahan banjir query.
- **Izin eksplisit menang atas CNAME-cloaking** dan cache dibersihkan otomatis saat aturan sendiri diubah.
- **Update lebih aman & hemat kuota**: GET bersyarat (ETag), validasi bentuk isi, tolak daftar yang menyusut >50%; "tidak berubah" kini dihitung sukses (bukan "Gagal memperbarui"). Pipeline menolak menimpa bila hasil menyusut >30% dan mencetak jumlah per sumber.
- Perbaikan kompatibilitas API 21-22 (`getSystemService` berbasis kelas) dan izin `ACCESS_NETWORK_STATE`.
- **Bypass per aplikasi**: tombol "Aplikasi dikecualikan" memilih aplikasi yang tidak lewat tunnel VPN (berlaku setelah proteksi dinyalakan ulang).
- **Penanda [tebakan]** di log: domain yang diblokir hanya oleh heuristik mode ketat (bukan daftar) diberi label, jadi kandidat salah blokir mudah ditemukan dan diizinkan.

## Peningkatan v1.4
- **DNS-over-TCP**: server TCP mini di dalam tunnel (handshake, segmentasi, FIN, RST) untuk klien yang jatuh ke TCP saat balasan terpotong. Query TCP diteruskan ke upstream lewat TCP juga, jadi balasan besar tidak terpotong. DoT/DoH (853/443) tetap ditolak.
- **Atribusi aplikasi** (Android 10+): log menampilkan aplikasi pemilik query yang diblokir.
- **"Ada yang rusak? Perbaiki cepat"**: daftar domain yang diblokir 2 menit terakhir (tebakan heuristik dicentang lebih dulu), izinkan 24 jam atau permanen.
- **Izin otomatis sementara** (bisa dimatikan): hanya untuk domain yang diblokir *semata-mata* oleh heuristik, berlabel lemah (telemetry/beacon/pixel), diminta >=4 kali dalam 20 detik oleh satu aplikasi non-browser. Berlaku 72 jam, maksimal 40 entri, terlihat dan bisa dihapus di "Aturan sendiri". Domain dari daftar blokir tidak pernah diizinkan otomatis.
- **Prefetch**: nama yang sering dipakai di-refresh sebelum TTL habis.
- **TTL blokir adaptif**: domain yang terus diulang aplikasi dijawab dengan TTL 15 menit agar klien berhenti menanyakan (hemat baterai).

## Peningkatan v1.5 (YouTube di browser)
- Skip iklan video memakai `<video>` pemutar utama (bukan pratinjau feed) dan **memulihkan status mute & kecepatan** milik pengguna setelah iklan; juga dipicu saat `yt-navigate-finish`.
- Stub `ad_status.js` (`google_ad_status=1`) agar pengecekan anti-adblock YouTube tidak gagal karena balasan kosong.
- Selektor tambahan: overlay/teks/gambar iklan pemutar, companion, promoted search, serta slot iklan di feed & Shorts (`:has()`, diabaikan otomatis di WebView lama).
- Batasan: bila YouTube menolak memutar video (enforcement sisi server), dialognya dihapus tetapi video bisa tetap diblokir; gunakan tombol ↗ atau muat ulang.

## Workflow (v1.5)
- **Build APK**: tidak berjalan untuk commit bot/README, membatalkan build lama pada branch yang sama, `timeout`, dan gagal jelas bila APK tidak terbentuk.
- **Tanda tangan tetap (disarankan)**: tanpa ini tiap build memakai keystore debug acak sehingga APK baru tidak bisa menimpa yang lama. Buat sekali di komputer:
  `keytool -genkeypair -v -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
  lalu `base64 -w0 debug.keystore` (di macOS: `base64 -i debug.keystore`) dan simpan hasilnya di *Settings > Secrets and variables > Actions* dengan nama `DEBUG_KEYSTORE_BASE64`. Simpan file keystore-nya; pemasangan pertama dengan kunci ini mengharuskan copot dulu versi lama.
- **Update blocklist**: antrean tunggal (`concurrency`), push dengan `pull --rebase` + 3x coba ulang, tidak gagal bila `cosmetic.txt` belum ada; pipeline mencoba ulang unduhan sumber 3x dan tidak menimpa `cosmetic.txt` bila menyusut >30%.
