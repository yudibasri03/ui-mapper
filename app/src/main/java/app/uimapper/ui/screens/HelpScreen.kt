package app.uimapper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private class HelpStep(val title: String, val body: String)

private class HelpLegend(val color: Color, val title: String, val body: String)

private class HelpExport(val format: String, val body: String)

private val HelpSteps = listOf(
    HelpStep(
        "Aktifkan layanan",
        "Di beranda, ketuk \"Buka Pengaturan Aksesibilitas\", pilih \"UI Mapper – Inspektur UI\", lalu " +
            "aktifkan. Di Android 13 ke atas, jika tombolnya abu-abu (setelan terbatas), buka Info Aplikasi " +
            "UI Mapper, ketuk menu ⋮, pilih \"Izinkan setelan terbatas\", lalu coba lagi.",
    ),
    HelpStep(
        "Pilih aplikasi",
        "Ketuk \"Pilih aplikasi\" dan cari aplikasi yang ingin dipetakan, berdasarkan nama atau nama paket.",
    ),
    HelpStep(
        "Rekam rute navigasi",
        "Ketuk \"Rekam rute navigasi\". Aplikasi target terbuka; gunakan seperti biasa. Setiap kali layar " +
            "berganti, UI Mapper menyimpan struktur layar baru beserta tombol yang Anda ketuk sebagai rute. " +
            "Anda sendiri yang mengetuk dan menjelajah; UI Mapper hanya mengamati.",
    ),
    HelpStep(
        "Inspeksi elemen",
        "Ketuk gelembung melayang, pilih \"Inspeksi elemen\", lalu ketuk elemen mana pun di layar untuk " +
            "melihat kelas, resource-id, teks, deskripsi konten, posisi (bounds), status (flag), dan aksi " +
            "yang tersedia. Selama mode inspeksi, ketukan dipakai untuk memilih elemen.",
    ),
    HelpStep(
        "Hentikan & lihat peta",
        "Hentikan perekaman dari gelembung atau dari beranda. Buka sesi untuk melihat daftar layar, peta " +
            "navigasi, dan rute tombol dari layar awal ke setiap layar.",
    ),
    HelpStep(
        "Ekspor",
        "Dari detail sesi, ekspor ke JSON, Mermaid, DOT, CSV, laporan HTML, atau ZIP, lalu bagikan atau " +
            "simpan ke folder Download.",
    ),
)

private val HelpLegends = listOf(
    HelpLegend(
        Color(0xFF2E9E4F),
        "Hijau: dapat diketuk",
        "Elemen yang bisa diklik atau ditekan lama (tombol, item daftar, tab).",
    ),
    HelpLegend(
        Color(0xFF1E88E5),
        "Biru: teks",
        "Elemen yang berisi teks atau deskripsi konten tetapi tidak dapat diketuk.",
    ),
    HelpLegend(
        Color(0xFFFB8C00),
        "Oranye: dipilih",
        "Elemen yang sedang Anda inspeksi; propertinya tampil di panel.",
    ),
)

private val HelpExports = listOf(
    HelpExport(
        "JSON",
        "Data lengkap sesi: setiap layar dengan pohon elemennya (kelas, resource-id, teks, deskripsi, " +
            "bounds, flag, aksi) serta semua rute navigasi. Cocok untuk diolah lebih lanjut.",
    ),
    HelpExport(
        "Mermaid",
        "Diagram alur navigasi dalam teks Mermaid; bisa ditempel ke Markdown, GitHub, atau alat dokumentasi.",
    ),
    HelpExport(
        "DOT",
        "Graf navigasi format Graphviz untuk dirender dengan dot atau alat graf lain.",
    ),
    HelpExport(
        "CSV",
        "Tabel layar dan rute untuk dibuka di spreadsheet.",
    ),
    HelpExport(
        "HTML",
        "Laporan mandiri yang dapat dibuka di browser mana pun: ringkasan, peta navigasi, layar, dan rute.",
    ),
    HelpExport(
        "ZIP",
        "Satu arsip berisi berkas-berkas ekspor di atas beserta tangkapan layar.",
    ),
)

private val HelpLimitations = listOf(
    "Aplikasi Flutter, game, dan aplikasi berbasis canvas sering hanya menampilkan sedikit elemen ke " +
        "layanan aksesibilitas, sehingga pohon elemennya tidak lengkap.",
    "Isi WebView bervariasi, tergantung apakah halaman web menyediakan informasi aksesibilitas.",
    "Layar yang diamankan (mis. aplikasi bank atau pembayaran) memblokir tangkapan layar; hasilnya bisa " +
        "hitam atau kosong. Struktur elemennya biasanya tetap terbaca.",
    "Sebagian aplikasi membatasi atau mendeteksi layanan aksesibilitas dan bisa menolak berjalan.",
    "Layar yang sangat dinamis (feed, carousel, iklan, penghitung waktu) bisa terpecah menjadi beberapa " +
        "layar. Turunkan ambang kemiripan di Pengaturan agar digabung, atau naikkan agar dipisah lebih rinci.",
    "Rute hanya tercatat untuk tombol yang benar-benar Anda ketuk selama perekaman.",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Bantuan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Apa itu UI Mapper?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "UI Mapper adalah inspektur UI untuk riset UX dan QA. Aplikasi ini memetakan " +
                            "elemen tampilan (kelas, resource-id, teks, posisi, aksi) dan rute navigasi " +
                            "tombol antar-layar dari aplikasi lain di ponsel ini, sambil Anda sendiri " +
                            "menjelajahinya. Secara bawaan UI Mapper hanya mengamati dan tidak pernah " +
                            "mengetuk apa pun untuk Anda; satu-satunya pengecualian adalah fitur edit teks " +
                            "opsional yang mati secara bawaan.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            HelpSection(title = "Langkah penggunaan", icon = Icons.Outlined.School) {
                HelpSteps.forEachIndexed { index, step ->
                    HelpStepRow(number = index + 1, step = step)
                }
            }

            HelpSection(title = "Warna pada inspektur", icon = Icons.Outlined.Palette) {
                HelpLegends.forEach { legend -> HelpLegendRow(legend) }
            }

            HelpSection(title = "Edit teks (opsional)", icon = Icons.Outlined.Edit) {
                HelpBullet(
                    "Fitur ini mati secara bawaan. Aktifkan dulu \"Izinkan edit teks pada aplikasi target\" " +
                        "di Pengaturan.",
                )
                HelpBullet(
                    "Buka mode inspeksi dari gelembung, pilih kolom yang bisa diedit (editable), ketik teks " +
                        "pada panel, lalu ketuk Terapkan. Kosongkan teks lalu Terapkan untuk menghapus isi kolom.",
                )
                HelpBullet(
                    "Berbeda dari fitur lain, ini membuat UI Mapper beraksi pada aplikasi target " +
                        "(mengisi/menghapus kolom) atas perintah Anda, bukan lagi hanya mengamati. Gunakan hanya " +
                        "pada aplikasi milik Anda atau yang berwenang Anda uji. Teks yang Anda masukkan tidak disimpan.",
                )
            }

            HelpSection(title = "Isi berkas ekspor", icon = Icons.Outlined.FileDownload) {
                HelpExports.forEach { export -> HelpExportRow(export) }
            }

            HelpSection(title = "Keterbatasan", icon = Icons.Outlined.Warning) {
                HelpLimitations.forEach { text -> HelpBullet(text) }
            }

            HelpSection(title = "Privasi", icon = Icons.Outlined.Lock) {
                HelpBullet("Semua data disimpan lokal; UI Mapper tidak memiliki izin internet.")
                HelpBullet(
                    "Isi kolom kata sandi dan teks yang Anda ketik tidak disimpan di data elemen; kolom " +
                        "isian, keyboard dan bilah status ditutup pada tangkapan layar. Teks lain yang tampil " +
                        "di layar tetap bisa terlihat di tangkapan layar.",
                )
                HelpBullet(
                    "Secara bawaan UI Mapper tidak pernah mengetuk, menggeser, atau menjalankan aksi apa pun " +
                        "di aplikasi lain. Satu-satunya pengecualian adalah fitur edit teks opsional (mati " +
                        "secara bawaan): bila Anda mengaktifkannya, UI Mapper dapat mengisi/menghapus teks kolom " +
                        "input atas perintah Anda; teks itu tidak disimpan.",
                )
                HelpBullet("Data hanya keluar dari perangkat jika Anda sendiri mengekspor dan membagikannya.")
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HelpSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
private fun HelpStepRow(number: Int, step: HelpStep) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = step.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelpLegendRow(legend: HelpLegend) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 24.dp)
                .background(legend.color.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                .border(2.dp, legend.color, RoundedCornerShape(4.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = legend.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = legend.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HelpExportRow(export: HelpExport) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(6.dp))
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = export.format,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = export.body,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HelpBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}
