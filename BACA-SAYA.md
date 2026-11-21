# Buku Cha Tea Solo - 2 Aplikasi: KARYAWAN dan OWNER

Satu paket ini menghasilkan DUA APK sekaligus:
- **ChaTea-karyawan.apk** ("Cha Tea Karyawan"): tab **Catat** (Catat Penjualan, Transaksi hari ini, Pengeluaran karyawan
  lainnya, Pengeluaran operasional) dan tab **Laporan** (penjualan, per menu, per hari; TANPA gaji/bonus/tunai bersih).
- **ChaTea-pemilik.apk** ("Cha Tea Owner"): semua fitur + tombol **Refresh** + lonceng permintaan dari karyawan.

## 1. Siapkan Google Sheets (sekali saja)
1. Google Sheet baru > Extensions > Apps Script. Tempel seluruh isi `Code.gs`.
2. Ganti DUA kunci di baris atas, harus BERBEDA dan rahasia:
   `KUNCI` (untuk karyawan) dan `KUNCI_OWNER` (untuk owner saja).
3. Jalankan fungsi `setup` (izinkan akses). Jika dulu sudah pernah: tempel Code.gs baru lalu Run `setup` lagi.
4. Deploy > New deployment > Web app. Execute as: Me. Who has access: Anyone. Salin "Web app URL".
   Setiap Code.gs diubah, buat New deployment / New version.

## 2. Isi `www/config.js`
Isi `url`, `key` (kunci karyawan), `ownerKey` (kunci owner), `ownerWa` (WhatsApp owner, format 628xxx), `empWa` (opsional).
Kunci owner OTOMATIS dihapus dari APK karyawan saat dibuat, jadi karyawan tidak mungkin memilikinya.

## 3. Buat kedua APK (gratis, GitHub)
Unggah SEMUA isi folder ini ke repository GitHub > Actions > "Build APK (Karyawan + Owner)" > Run workflow (5-10 menit).
Unduh `ChaTea-karyawan-apk` dan `ChaTea-pemilik-apk`. Pasang di HP masing-masing (izinkan sumber tidak dikenal).

## Transaksi terkunci + kode sekali pakai
- Transaksi urut TERBARU di atas, bergembok. Karyawan tidak bisa langsung mengedit/menghapus.
- Karyawan tekan **Edit/Hapus** > tulis alasan > **Kirim permintaan ke owner** (juga membuka pesan WhatsApp ke owner).
- Owner melihat lonceng dan kartu "Permintaan dari karyawan" > **Setujui & buat kode** (6 angka) > **Kirim kode lewat WhatsApp**
  (atau **Tolak**).
- Karyawan memasukkan kode > transaksi dihapus, atau form edit terbuka. Kode berlaku 30 menit, SEKALI PAKAI, hanya untuk
  transaksi dan aksi itu; salah 5 kali = terkunci. Semua tercatat di sheet **Persetujuan**.
- Aturan ditegakkan di SERVER (Code.gs), bukan hanya tampilan: HP karyawan yang dimodifikasi pun tidak bisa mengubah/menghapus
  transaksi lama, dan harga transaksi baru dipaksa sama dengan menu owner.
- Owner mengubah/menghapus langsung tanpa kode.

## Stok (owner)
- **Stok bahan baku ( buah, susu, lain-lain )**: kartu stok di tab Catat dan daftar bahan di Pengaturan (Awal / Tambah, berkurang otomatis dari penjualan).
- **Stok Lainnya** (tab baru, terkunci password): plastik, gelas besar, gelas kecil, sedotan, gula tebu GMP, dll.
  Catat barang MASUK (beli) dan KELUAR (rusak/terbuang). Pemakaian harian berkurang OTOMATIS dari penjualan sesuai aturan
  "pakai per gelas Besar / Kecil / Tumbler" (bisa diubah, tumbler tidak memakai gelas). Angka bawaan gula tebu GMP hanyalah
  perkiraan: sesuaikan dengan takaran sebenarnya.
- **AI Pintar > Laporan stok & belanja otomatis**: menghitung barang mana yang HABIS / KRITIS / SEGERA habis, perkiraan tanggal habis,
  dan jumlah yang harus dibeli (target cadangan default 7 hari). Tombol kirim daftar belanja lewat WhatsApp. Lencana angka di tab
  AI Pintar menunjukkan jumlah barang mendesak. Di Google Sheets: sheet **Stok Lainnya** (riwayat harian) dan **Daftar Belanja**.

## Sinkron
- Karyawan input > otomatis ke Google Sheets (Transaksi, Pengeluaran). Owner tekan **Refresh** > data karyawan masuk ke
  aplikasi owner dan Harian/Stok/Dashboard diperbarui (juga otomatis tiap 1 menit).
- Perubahan owner (menu, harga, bahan, tarif bonus, transaksi, pengeluaran) muncul di HP karyawan maksimal ~15 detik.

## Catatan
- Pengeluaran sehari sebaiknya diisi dari satu HP; jika dua HP mengubah daftar yang sama, yang terakhir terkirim dipakai.
- Jangan bagikan kunci owner. Jika bocor: ganti `KUNCI_OWNER` di Code.gs, New deployment, ganti config.js, bangun ulang APK.
- Untuk Google Play Store perlu build rilis bertanda tangan dan akun developer (USD 25).
