# UI Mapper Web

Ekstensi Google Chrome (Manifest V3) untuk **menginspeksi elemen UI** dan **memetakan rute
navigasi** aplikasi web. Ini adalah versi web dari aplikasi Android "UI Mapper": inspeksi elemen
apa pun (tag, id, kelas, atribut, teks, peran ARIA, bounds, selektor CSS, XPath, computed style),
lihat pohon hierarki DOM, dan **rekam rute navigasi** saat Anda menjelajah sebuah aplikasi web
(tautan/tombol mana di layar mana yang menuju URL/route mana). Sesi disimpan lokal dan
ditampilkan sebagai graf navigasi, lengkap dengan ekspor (JSON, Mermaid, DOT, CSV, HTML).

Semua teks antarmuka dalam Bahasa Indonesia. Vanilla JS/HTML/CSS, tanpa build step, tanpa
framework, tanpa dependensi npm.

## Memuat ekstensi (unpacked)

1. Buka `chrome://extensions`.
2. Aktifkan **Developer mode** (Mode pengembang) di kanan atas.
3. Klik **Load unpacked** (Muat yang belum dikemas).
4. Pilih folder `web-extension` ini (folder yang berisi `manifest.json`).
5. Ikon UI Mapper Web akan muncul di bilah alat. Klik ikonnya untuk membuka **side panel**.

## Cara pakai

- **Buka panel**: klik ikon ekstensi. Panel samping (side panel) terbuka untuk tab aktif.
- **Inspeksi**: nyalakan mode inspeksi, lalu arahkan/klik elemen pada halaman. Panel menampilkan
  tag, id, kelas, atribut, teks, peran, bounds, selektor CSS, XPath, dan pohon DOM. Inspeksi
  bersifat pasif (hanya menyorot), tidak mengubah halaman.
- **Rekam**: tekan "Rekam" untuk memulai sesi bagi origin tab aktif. Saat Anda mengklik tautan/
  tombol dan berpindah halaman/route, transisi terekam sebagai edge (klik, pindah, kembali,
  kirim, situs lain, buka). Layar dikelompokkan otomatis berdasarkan tanda tangan struktural.
- **Ekspor**: pada sebuah sesi, pilih format (JSON lengkap, Mermaid, DOT, CSV elemen, atau
  laporan HTML mandiri) untuk mengunduh berkasnya. Laporan HTML berisi peta navigasi (SVG),
  daftar rute, tabel transisi, tabel elemen per layar, dan hierarki DOM lengkap, dan dapat dibuka
  tanpa internet.
- **Edit (opsional)**: mode edit memungkinkan mengisi nilai sebuah input pada halaman untuk uji
  coba. Nilai yang Anda ketik **tidak pernah disimpan**.

## Rasional izin

- `storage` - menyimpan sesi, ringkasan layar, dan pohon DOM di `chrome.storage.local`.
- `activeTab` / `tabs` - mengetahui tab aktif dan URL-nya untuk menyuntikkan skrip dan melacak
  navigasi selama merekam.
- `scripting` - menyuntikkan content script inspeksi hanya saat diminta (bukan pada setiap
  halaman). Tidak ada content script yang berjalan otomatis.
- `sidePanel` - antarmuka utama ekstensi berupa panel samping.
- `host_permissions: <all_urls>` - agar inspeksi/rekam bisa dipakai di situs web mana pun yang
  Anda buka. Skrip tetap hanya disuntikkan saat Anda menekan inspeksi/rekam.

## Privasi

- **Lokal sepenuhnya**: ekstensi tidak pernah melakukan permintaan jaringan (tidak ada
  fetch/XHR/beacon). Semua data tinggal di perangkat Anda di `chrome.storage.local`.
- **Tanpa tangkapan sandi**: nilai kolom kata sandi tidak pernah dibaca atau disimpan.
- **Tanpa menyimpan ketikan**: teks yang Anda ketik lewat mode edit tidak disimpan; ia hanya
  diterapkan ke halaman pada aksi eksplisit Anda.
- Ekstensi hanya menyentuh halaman ketika Anda secara eksplisit melakukannya (sorotan inspeksi
  pasif; perekaman hanya mengamati; edit menetapkan nilai hanya atas aksi pengguna).

## Batasan

- Tidak dapat menyuntik skrip ke `chrome://`, Chrome Web Store, halaman internal Chrome, atau
  berkas PDF bawaan. Buka situs web biasa untuk memakainya.
- Iframe lintas-origin tidak ditelusuri (hanya dokumen utama tab yang dipetakan).
- Aplikasi berat berbasis canvas/WebGL mengekspos sedikit DOM, sehingga inspeksinya terbatas.
- Pengelompokan layar bersifat heuristik (tanda tangan struktural + kemiripan Jaccard); layar
  yang sangat mirip dapat tergabung, dan dapat dinamai ulang secara manual.

## Struktur berkas

```
web-extension/
  manifest.json           Manifest MV3.
  background.js           Service worker (ES module): state, injeksi, pesan, persistensi.
  lib/
    model.js              Tipe data bersama + helper murni (label, flatten, escape, csv).
    signature.js          Tanda tangan struktural layar (fitur, hash FNV-1a, Jaccard).
    routeGraph.js         Kueri graf + tata letak peta navigasi (murni).
    exporters.js          Ekspor JSON / Mermaid / DOT / CSV / HTML (murni).
    store.js              Helper chrome.storage.local (sesi, layar, edge).
  content/
    inspector.js          Content script (inspeksi, perekaman, editor) - disuntik saat diminta.
  sidepanel/
    sidepanel.html/.js/.css  Antarmuka panel samping.
  icons/                  Ikon 16/48/128.
```

> Catatan: `content/inspector.js` dan berkas di `sidepanel/` disediakan oleh bagian lain dari
> proyek ini; berkas dalam daftar di atas yang berada di luar cakupan modul ini tidak dibuat oleh
> modul backend/pustaka.

## Model data & pesan (ringkas)

- `WNode` - satu elemen DOM tertangkap (idx pre-order, tag, id, kelas, peran, teks aman, atribut
  allowlist, rect, flag clickable/editable/password, selektor, xpath, anak).
- `Screen` / `ScreenSummary` - tangkapan satu layar (lengkap dengan root) dan ringkasannya.
- `Edge` - transisi navigasi berarah antar layar (`click`, `nav`, `back`, `external`, `submit`,
  `launch`).
- `Session` - sesi per-origin berisi ringkasan layar + edge.

Penyimpanan `chrome.storage.local`: kunci `ui` (pengaturan), `sessions` (Session[] tanpa pohon),
`screen:<sessionId>:<screenId>` (Screen lengkap dengan root).
