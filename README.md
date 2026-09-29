# UI Mapper

Aplikasi Android untuk **menginspeksi elemen UI** aplikasi lain di HP dan **memetakan rute navigasi tombol** (layar mana → tombol apa → ke layar mana). Cocok untuk riset UI/UX, QA, dokumentasi alur aplikasi, dan persiapan test automation.

Secara bawaan UI Mapper hanya **mengamati**. Ia tidak pernah mengetuk atau melakukan aksi apa pun di aplikasi lain. Pemetaan terjadi saat Anda sendiri menjelajahi aplikasinya. Satu-satunya pengecualian adalah fitur **edit teks** yang **mati secara bawaan** dan hanya berjalan atas perintah Anda (lihat [Privasi](#privasi)).

## Fitur

- **Inspektur elemen (overlay mengambang):** ketuk elemen mana saja di aplikasi yang sedang terbuka untuk melihat class, resource-id, teks, content-description, hint, bounds, flag (clickable, scrollable, editable, …), daftar aksi, dan XPath. Tersedia navigasi induk/anak dan tombol salin.
- **Edit teks (opsional, mati secara bawaan):** setelah Anda mengaktifkannya di Pengaturan, panel inspeksi dapat mengisi atau menghapus teks kolom input yang sedang Anda pilih di aplikasi target, atas perintah Anda. Ini satu-satunya fitur yang membuat UI Mapper *beraksi* pada aplikasi lain; teks yang dimasukkan tidak disimpan. Ditujukan untuk aplikasi milik Anda atau yang berwenang Anda uji.
- **Perekam rute navigasi:** selama Anda memakai aplikasi target, setiap perpindahan layar dicatat beserta tombol yang diketuk. Transisi Kembali (back) dan perpindahan ke aplikasi lain dikenali otomatis.
- **Deteksi layar unik:** tiap tangkapan diberi fingerprint struktural, sehingga layar yang sama dengan data berbeda (isi list, angka, nama) tetap dikenali sebagai satu layar. Tingkat kemiripannya bisa diatur.
- **Peta navigasi:** graf interaktif (zoom/geser) dan daftar "rute dari layar awal". Ada juga pencari rute terpendek antar dua layar.
- **Detail layar:** screenshot (Android 11+) dengan kotak elemen, pohon hierarki lengkap, properti, daftar elemen yang bisa diklik beserta tujuannya.
- **Ekspor:** JSON lengkap, Mermaid, Graphviz DOT, CSV elemen, laporan HTML offline (dengan peta SVG dan screenshot), atau ZIP berisi semuanya.

## Privasi

- Tanpa izin internet: semua data tersimpan lokal di perangkat.
- Isi kolom kata sandi dan teks yang Anda ketik **tidak disimpan** di data elemen; untuk kolom input hanya hint yang dicatat.
- Pada screenshot, area kolom input/kata sandi dan keyboard ditutup sebelum disimpan. Konten lain yang terlihat di layar (misalnya notifikasi) tetap bisa ikut terekam, jadi matikan screenshot di Pengaturan bila perlu.
- **Edit teks (opsional, mati secara bawaan):** bila Anda mengaktifkannya di Pengaturan, UI Mapper dapat **mengisi atau menghapus** teks pada kolom input aplikasi yang sedang Anda inspeksi, atas perintah Anda. Ini membuat UI Mapper **beraksi** pada aplikasi target, bukan lagi hanya mengamati — tetap dimulai oleh Anda (tidak ada otomatisasi ketukan/gestur). Teks yang Anda masukkan **tidak disimpan maupun dicatat**. Ditujukan hanya untuk aplikasi milik Anda atau yang berwenang Anda uji.

## Build

Kebutuhan: JDK 17 dan Android SDK (platform 35).

```bash
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Cara lain lewat **GitHub Actions**: setiap push ke `main` (atau *Run workflow* di tab Actions) menghasilkan artifact `ui-mapper-debug-apk`.

> Catatan: APK debug hasil build di mesin berbeda memakai kunci tanda tangan berbeda. Uninstall versi lama sebelum memasang APK dari sumber lain.

## Cara pakai

1. Pasang APK, buka **UI Mapper**.
2. Ketuk **Buka Pengaturan Aksesibilitas** → aktifkan **UI Mapper – Inspektur UI**.
   - Android 13+: kalau sakelarnya terkunci ("setelan terbatas"), buka **Info Aplikasi → ⋮ → Izinkan setelan terbatas**, lalu ulangi.
3. **Pilih aplikasi** yang ingin dipetakan.
4. **Rekam rute navigasi** → aplikasi target terbuka, lalu jelajahi seperti biasa. Hentikan lewat gelembung overlay atau dari UI Mapper.
5. **Inspeksi elemen**: ketuk gelembung → *Inspeksi elemen* → ketuk elemen apa pun.
6. Buka **Sesi** untuk melihat layar, peta, rute, dan ekspor.

## Keterbatasan

- Aplikasi Flutter, game, atau berbasis canvas hanya mengekspos sedikit elemen aksesibilitas.
- Layar yang diproteksi (FLAG_SECURE, misalnya aplikasi bank) menghasilkan screenshot hitam atau gagal.
- Beberapa aplikasi membatasi atau mendeteksi layanan aksesibilitas.
- Layar yang sangat dinamis bisa terpecah menjadi beberapa layar. Atur *ambang kemiripan* di Pengaturan.

## Struktur kode

| Paket | Isi |
|---|---|
| `model` | Model data (UiNode, ScreenSnapshot, NavEdge, Session) |
| `core` | Capture pohon node, fingerprint layar, utilitas pohon, pencari rute |
| `data` | Penyimpanan sesi berbasis file, pengaturan |
| `service` | AccessibilityService + perekam rute |
| `overlay` | Gelembung mengambang, layer inspeksi, panel properti |
| `export` | Ekspor JSON / Mermaid / DOT / CSV / HTML / ZIP |
| `ui` | Aplikasi Jetpack Compose (beranda, sesi, detail layar, peta) |
