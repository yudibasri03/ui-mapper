package app.uimapper.ui.screens

import android.os.Build
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.uimapper.BuildConfig
import app.uimapper.data.AppSettings
import app.uimapper.data.SessionStore
import app.uimapper.service.ServiceBridge
import app.uimapper.service.ServiceCommand
import app.uimapper.service.ServiceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private val SettingsLocale: Locale = Locale.forLanguageTag("id-ID")

// Mirrors the defaults in AppSettings.
private const val SETTINGS_DEFAULT_SIMILARITY = 0.82f
private const val SETTINGS_DEFAULT_SETTLE_MS = 800L
private const val SETTINGS_SIMILARITY_MIN = 0.50f
private const val SETTINGS_SIMILARITY_MAX = 0.99f
private const val SETTINGS_SETTLE_MIN = 200L
private const val SETTINGS_SETTLE_MAX = 3000L
private const val SETTINGS_SETTLE_STEP = 50

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val sessions by SessionStore.sessions.collectAsStateWithLifecycle()
    val serviceState by ServiceBridge.state.collectAsStateWithLifecycle()

    var similarity by remember { mutableFloatStateOf(AppSettings.similarityThreshold) }
    var settleMs by remember {
        mutableFloatStateOf(AppSettings.settleMs.coerceIn(SETTINGS_SETTLE_MIN, SETTINGS_SETTLE_MAX).toFloat())
    }
    var screenshots by remember { mutableStateOf(AppSettings.captureScreenshots) }
    var overlayOnConnect by remember { mutableStateOf(AppSettings.overlayOnConnect) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val screenshotsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    val recording = serviceState.mode == ServiceMode.RECORDING

    val sessionIds = sessions.map { it.id }
    val totalScreens = sessions.sumOf { it.screens.size }
    val lastUpdate = sessions.maxOfOrNull { it.updatedAt } ?: 0L
    // Lint false positive (compose-runtime 1.7): the keyed produceState overloads are always
    // flagged even though the producer assigns `value` below.
    @android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
    val storageBytes by produceState<Long?>(initialValue = null, sessionIds, lastUpdate) {
        value = withContext(Dispatchers.IO) {
            // The whole folder, including session folders that could not be read (they still take space).
            runCatching { settingsDirSize(SessionStore.rootDir()) }.getOrNull()
        }
    }

    fun resetDefaults() {
        similarity = SETTINGS_DEFAULT_SIMILARITY
        settleMs = SETTINGS_DEFAULT_SETTLE_MS.toFloat()
        screenshots = true
        overlayOnConnect = true
        AppSettings.similarityThreshold = SETTINGS_DEFAULT_SIMILARITY
        AppSettings.settleMs = SETTINGS_DEFAULT_SETTLE_MS
        AppSettings.captureScreenshots = true
        AppSettings.overlayOnConnect = true
        scope.launch { snackbar.showSnackbar("Pengaturan dikembalikan ke bawaan") }
    }

    fun deleteAllSessions() {
        if (deleting) return
        deleting = true
        scope.launch {
            // Stop an active recording first so it does not keep writing into a deleted session.
            if (ServiceBridge.state.value.mode == ServiceMode.RECORDING) {
                ServiceBridge.send(ServiceCommand.Stop)
            }
            val appContext = context.applicationContext
            val deleted = withContext(Dispatchers.IO) {
                // Every folder under sessions/, also ones that could not be read as a session.
                val count = runCatching { SessionStore.deleteAll() }.getOrDefault(0)
                // Exported copies of the sessions live in cacheDir/exports; remove them as well.
                runCatching { File(appContext.cacheDir, "exports").deleteRecursively() }
                count
            }
            deleting = false
            snackbar.showSnackbar("$deleted sesi dihapus")
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Screen mapping ----
            SettingsSection(title = "Pemetaan layar", icon = Icons.Outlined.Tune) {
                SettingsValueHeader(
                    title = "Ambang kemiripan layar",
                    value = String.format(SettingsLocale, "%.2f", similarity),
                )
                Slider(
                    value = similarity,
                    onValueChange = { similarity = ((it * 100f).roundToInt() / 100f) },
                    onValueChangeFinished = { AppSettings.similarityThreshold = similarity },
                    valueRange = SETTINGS_SIMILARITY_MIN..SETTINGS_SIMILARITY_MAX,
                )
                SettingsNote(
                    "Seberapa mirip dua tangkapan agar dianggap layar yang sama. Nilai lebih tinggi = layar " +
                        "dipisah lebih rinci (perubahan kecil menjadi layar baru). Nilai lebih rendah = " +
                        "variasi kecil (feed, carousel, iklan) digabung menjadi satu layar. Bawaan: 0,82.",
                )

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                SettingsValueHeader(
                    title = "Jeda tunggu sebelum menangkap",
                    value = "${settleMs.roundToInt()} ms",
                )
                Slider(
                    value = settleMs,
                    onValueChange = {
                        settleMs = ((it / SETTINGS_SETTLE_STEP).roundToInt() * SETTINGS_SETTLE_STEP)
                            .toFloat()
                            .coerceIn(SETTINGS_SETTLE_MIN.toFloat(), SETTINGS_SETTLE_MAX.toFloat())
                    },
                    onValueChangeFinished = { AppSettings.settleMs = settleMs.roundToInt().toLong() },
                    valueRange = SETTINGS_SETTLE_MIN.toFloat()..SETTINGS_SETTLE_MAX.toFloat(),
                )
                SettingsNote(
                    "Waktu tampilan harus diam (tanpa perubahan) sebelum layar ditangkap. Naikkan untuk " +
                        "aplikasi dengan animasi panjang atau konten yang dimuat lambat. Bawaan: 800 ms.",
                )
            }

            // ---- Capture & bubble ----
            SettingsSection(title = "Tangkapan & gelembung", icon = Icons.Outlined.PhotoCamera) {
                SettingsSwitchRow(
                    title = "Simpan tangkapan layar",
                    description = buildString {
                        append("Menyimpan gambar setiap layar baru. Memerlukan Android 11 atau lebih baru. ")
                        append("Kolom isian, keyboard dan bilah status/notifikasi ditutup pada gambar, tetapi teks ")
                        append("lain yang tampil di layar ikut tersimpan. ")
                        append("Layar yang diamankan (mis. aplikasi bank atau pembayaran) dapat tampak hitam.")
                        if (!screenshotsSupported) {
                            append(" Tidak tersedia di perangkat ini (Android ${Build.VERSION.RELEASE}).")
                        }
                    },
                    checked = screenshots && screenshotsSupported,
                    enabled = screenshotsSupported,
                    onCheckedChange = {
                        screenshots = it
                        AppSettings.captureScreenshots = it
                    },
                )
                HorizontalDivider()
                SettingsSwitchRow(
                    title = "Tampilkan gelembung saat layanan terhubung",
                    description = "Gelembung inspektur muncul otomatis setiap kali layanan aksesibilitas " +
                        "UI Mapper aktif.",
                    checked = overlayOnConnect,
                    enabled = true,
                    onCheckedChange = {
                        overlayOnConnect = it
                        AppSettings.overlayOnConnect = it
                    },
                )
                TextButton(onClick = ::resetDefaults) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Kembalikan pengaturan bawaan")
                }
            }

            // ---- Privacy ----
            SettingsSection(title = "Privasi", icon = Icons.Outlined.Lock) {
                SettingsBullet(
                    Icons.Outlined.PhoneAndroid,
                    "Semua data (struktur layar, rute, tangkapan layar) disimpan lokal di perangkat ini.",
                )
                SettingsBullet(
                    Icons.Outlined.CloudOff,
                    "UI Mapper tidak memiliki izin internet, sehingga tidak ada data yang dikirim ke mana pun. " +
                        "Data hanya keluar jika Anda sendiri mengekspor dan membagikannya.",
                )
                SettingsBullet(
                    Icons.Outlined.Keyboard,
                    "Isi kolom kata sandi dan teks yang Anda ketik di kolom isian tidak disimpan di data " +
                        "elemen, dan kolom isian serta keyboard ditutup pada tangkapan layar.",
                )
                SettingsBullet(
                    Icons.Outlined.Visibility,
                    "UI Mapper hanya mengamati. Aplikasi ini tidak pernah mengetuk, menggeser, atau " +
                        "menjalankan aksi apa pun di aplikasi lain.",
                )
            }

            // ---- Storage ----
            SettingsSection(title = "Penyimpanan", icon = Icons.Outlined.Storage) {
                SettingsKeyValue("Jumlah sesi", sessions.size.toString())
                SettingsKeyValue("Total layar", totalScreens.toString())
                SettingsKeyValue(
                    "Ukuran data",
                    storageBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "Menghitung...",
                )
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = sessions.isNotEmpty() && !deleting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    }
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Hapus semua sesi")
                }
            }

            // ---- About ----
            SettingsSection(title = "Tentang", icon = Icons.Outlined.Info) {
                Text(
                    text = "UI Mapper ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                SettingsNote(
                    "Inspektur UI dan pemeta rute navigasi untuk riset UX dan QA. Memetakan elemen dan " +
                        "perpindahan layar sambil Anda sendiri menjelajahi aplikasi.",
                )
                SettingsNote("Perangkat: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            }

            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("Hapus semua sesi?") },
            text = {
                Text(
                    buildString {
                        append("${sessions.size} sesi beserta semua layar, tangkapan layar, rute, dan berkas ")
                        append("ekspor akan dihapus permanen dari perangkat ini. Tindakan ini tidak dapat dibatalkan.")
                        if (recording) append("\n\nPerekaman yang sedang berjalan akan dihentikan.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        deleteAllSessions()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Batal") }
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
private fun SettingsValueHeader(title: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsBullet(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsKeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Total size of every file below [dir]; blocking, call on Dispatchers.IO. */
private fun settingsDirSize(dir: File): Long =
    if (!dir.exists()) 0L else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
