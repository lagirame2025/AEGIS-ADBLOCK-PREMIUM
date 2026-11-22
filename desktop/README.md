# Aegis AdBlock untuk Windows & Browser (v1.8.1)

Dua lapis yang saling melengkapi, semuanya lokal (tanpa akun, tanpa kirim data):

| Lapis | Apa yang dilakukan |
|---|---|
| **AegisSetup.exe** (Windows 10/11 x64) | Memasang filter DNS lokal (`aegis.exe`, jalan otomatis saat Windows menyala, tanpa jendela). DNS Windows diarahkan ke Aegis: iklan, tracker, **judol**, **scam/phishing/malware** diblokir di **semua aplikasi**. Dasbor: `http://127.0.0.1:8053`. |
| **Ekstensi browser** | Menangani yang tidak bisa dijangkau DNS: iklan YouTube, iklan berlabel di media sosial, pop-up/overlay jebakan, clickbait, komentar judol, tautan scam, halaman phishing. |

## Pasang
1. Jalankan `AegisSetup.exe` (setujui UAC). Pilih **Pasang**. Installer menguji dulu bahwa filter menjawab sebelum mengubah DNS; bila gagal, DNS Anda tidak disentuh. DNS cadangan dipasang agar internet tidak putus bila Aegis berhenti. Copot lewat *Pengaturan → Aplikasi* atau `AegisSetup.exe /UNINSTALL` (DNS dipulihkan).
2. Pasang ekstensi: buka `PASANG-EKSTENSI.html` di folder instalasi (`C:\Program Files\Aegis\extensions`).
   - **Chrome / Edge / Opera / Brave / Vivaldi**: Mode pengembang → *Load unpacked* → folder `chromium`.
   - **Firefox**: `ext-firefox.zip` perlu ditandatangani (gratis di AMO, jalur *unlisted*) untuk pemasangan permanen; untuk uji coba pakai *Load Temporary Add-on*.
   - **Safari (macOS)**: `xcrun safari-web-extension-converter safari --app-name Aegis` lalu build di Xcode.

Opsi senyap: `AegisSetup.exe /S` (tambahan: `/NOFAILSAFE`, `/NODOH`, `/DIR=C:\path`).

## Smart engine (lokal, tanpa cloud)
- **Skor host/URL**: punycode, TLD berisiko, pola nama judol (gacor, maxwin, togel, slot…), penyamaran merek (50+ merek bank/e-commerce/e-wallet), kata pancingan, pemendek tautan, `@` dalam URL, unduhan APK, IP langsung. Algoritma identik di Go (daemon) dan JS (ekstensi), dijaga `shared/vectors.json`.
- **Teks**: deteksi spam judol di komentar meski disamarkan huruf unicode (𝗦𝗟𝗢𝗧), leetspeak (g4c0r), spasi disisipkan, homoglif Sirilik; modus penipuan (hadiah, OTP, paket, undangan APK).
- **Smart Ads**: pengklasifikasi iklan berbasis fitur (regresi logistik berbobot) untuk elemen iklan yang lolos dari daftar: nama kelas, label "Iklan/Sponsored", ukuran slot IAB, iframe lintas-situs, tautan sponsor, elemen menempel; dikurangi sinyal konten asli (artikel, formulir, video, navigasi).
- **Daftar blokir**: StevenBlack, AdGuard DNS, HaGeZi (Pro, Pop-up, Gambling, Fake, Threat Intel), URLhaus, Phishing Army; diperbarui otomatis, dengan pengaman bila hasil unduhan menyusut tajam.

## Bangun ulang
`./desktop/build.sh` (Linux/macOS/WSL; butuh Go ≥ 1.21, Node ≥ 18, zip). Menjalankan semua tes, membangun ekstensi dan `AegisSetup.exe`/`aegis.exe` ke `desktop/dist`. Atau jalankan workflow GitHub "Build Desktop".

## Keterbatasan jujur
- Installer **belum ditandatangani** kode, jadi Windows SmartScreen akan memperingatkan ("Windows protected your PC" → *More info* → *Run anyway*).
- Ekstensi belum ada di toko resmi; pemasangan lewat mode pengembang/penandatanganan sendiri (lihat di atas). Browser tidak mengizinkan pemasangan ekstensi diam-diam dari luar toko pada PC pribadi.
- Iklan YouTube yang disisipkan server di aliran video bisa berubah kapan saja; skrip Aegis disesuaikan dengan pola yang ada, tetapi tidak ada pemblokir yang bisa menjamin 100%.
- Kode Windows (pengubahan DNS, Scheduled Task, registri) diuji lewat simulasi di Linux dan dikompilasi silang; belum dijalankan di mesin Windows sungguhan.
