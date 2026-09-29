package app.uimapper.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.uimapper.core.AppInfo
import app.uimapper.data.AppSettings
import app.uimapper.data.SessionStore
import app.uimapper.model.Session
import app.uimapper.model.SessionMode
import app.uimapper.service.ServiceBridge
import app.uimapper.service.ServiceCommand
import app.uimapper.service.ServiceMode
import app.uimapper.service.ServiceState
import app.uimapper.ui.components.storeErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val HOME_MSG_NOT_CONNECTED = "Layanan belum terhubung"
private const val HOME_RECENT_LIMIT = 3

private val HomeLocale: Locale = Locale.forLanguageTag("id-ID")
private val HomeDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", HomeLocale)
private val HomeDateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", HomeLocale)

private val HomeGreenLight = Color(0xFF1B7F3B)
private val HomeGreenDark = Color(0xFF7BD88F)
private val HomeRecordRed = Color(0xFFD93025)

/** Result of loading an app icon off the main thread. */
private class HomeIconResult(val bitmap: ImageBitmap?, val installed: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPickApp: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val state by ServiceBridge.state.collectAsStateWithLifecycle()
    val sessions by SessionStore.sessions.collectAsStateWithLifecycle()
    val sessionsLoading by SessionStore.loading.collectAsStateWithLifecycle()
    val settingsVersion by AppSettings.version.collectAsStateWithLifecycle()

    // The user toggles the service in system Settings, so re-check on every resume.
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    var serviceEnabled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resumeTick, state.connected) {
        serviceEnabled = withContext(Dispatchers.IO) {
            runCatching { ServiceBridge.isServiceEnabled(context) }.getOrDefault(false)
        }
    }

    val targetPkg = remember(settingsVersion) { AppSettings.targetPkg }
    val targetLabel = remember(settingsVersion) { AppSettings.targetLabel }

    // Lint false positive (compose-runtime 1.7): the keyed produceState overloads are always
    // flagged even though the producer assigns `value` below.
    @android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
    val now by produceState(initialValue = System.currentTimeMillis(), resumeTick) {
        value = System.currentTimeMillis()
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }

    var starting by remember { mutableStateOf(false) }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun openSystemScreen(open: (Context) -> Unit) {
        try {
            open(context)
        } catch (_: ActivityNotFoundException) {
            showMessage("Tidak dapat membuka pengaturan di perangkat ini")
        } catch (_: SecurityException) {
            showMessage("Tidak dapat membuka pengaturan di perangkat ini")
        }
    }

    fun startRecording() {
        val pkg = targetPkg ?: return
        if (starting) return
        if (!ServiceBridge.state.value.connected) {
            showMessage(HOME_MSG_NOT_CONNECTED)
            return
        }
        starting = true
        scope.launch {
            try {
                val label = targetLabel?.takeIf { it.isNotBlank() } ?: pkg
                val session = withContext(Dispatchers.IO) {
                    SessionStore.create(
                        name = "$label · ${HomeDateTimeFormat.format(LocalDateTime.now())}",
                        targetPkg = pkg,
                        appLabel = label,
                        mode = SessionMode.RECORD,
                    )
                }
                // Make sure the bubble is there so recording can be stopped from inside the target app.
                if (!ServiceBridge.state.value.overlayVisible) {
                    ServiceBridge.send(ServiceCommand.SetOverlay(true))
                }
                val sent = ServiceBridge.send(
                    ServiceCommand.StartRecording(sessionId = session.id, targetPkg = pkg, launchTarget = true),
                )
                if (!sent) {
                    // Do not leave an empty session behind when the service went away meanwhile.
                    withContext(Dispatchers.IO) { SessionStore.delete(session.id) }
                }
                starting = false
                if (!sent) snackbar.showSnackbar(HOME_MSG_NOT_CONNECTED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. storage full: never crash the app (and the service running in the same process).
                showMessage(storeErrorMessage("Sesi tidak dapat dibuat", e))
            } finally {
                starting = false
            }
        }
    }

    fun startInspect() {
        val pkg = targetPkg ?: return
        if (!ServiceBridge.send(ServiceCommand.SetOverlay(true))) {
            showMessage(HOME_MSG_NOT_CONNECTED)
            return
        }
        scope.launch {
            val intent = withContext(Dispatchers.IO) {
                runCatching { AppInfo.launchIntent(context, pkg) }.getOrNull()
            }
            if (intent == null) {
                snackbar.showSnackbar("Aplikasi tidak dapat dibuka. Mungkin sudah dihapus.")
                return@launch
            }
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar("Aplikasi tidak dapat dibuka. Mungkin sudah dihapus.")
            } catch (_: SecurityException) {
                snackbar.showSnackbar("Aplikasi ini tidak mengizinkan dibuka dari aplikasi lain")
            }
        }
    }

    fun stopRecording() {
        if (!ServiceBridge.send(ServiceCommand.Stop)) showMessage(HOME_MSG_NOT_CONNECTED)
    }

    fun setOverlay(visible: Boolean) {
        if (!ServiceBridge.send(ServiceCommand.SetOverlay(visible))) showMessage(HOME_MSG_NOT_CONNECTED)
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val recording = state.mode == ServiceMode.RECORDING
    val activeSession = state.sessionId?.let { id -> sessions.firstOrNull { it.id == id } }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("UI Mapper") },
                actions = {
                    IconButton(onClick = onOpenLog) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Live log")
                    }
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = "Daftar sesi")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Pengaturan")
                    }
                    IconButton(onClick = onOpenHelp) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "Bantuan")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = homeContentPadding(inner, 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                HomeServiceStatusCard(
                    state = state,
                    enabled = serviceEnabled,
                    onOpenAccessibility = { openSystemScreen(ServiceBridge::openAccessibilitySettings) },
                    onOpenAppInfo = { openSystemScreen(ServiceBridge::openAppInfo) },
                    onOverlayChange = ::setOverlay,
                )
            }
            if (recording) {
                item(key = "recording") {
                    HomeRecordingCard(
                        state = state,
                        session = activeSession,
                        onStop = ::stopRecording,
                        onOpenMap = { state.sessionId?.let(onOpenSession) },
                        onOpenLog = onOpenLog,
                    )
                }
            }
            item(key = "target") {
                HomeTargetCard(pkg = targetPkg, label = targetLabel, onPickApp = onPickApp)
            }
            item(key = "start") {
                HomeStartCard(
                    connected = state.connected,
                    hasTarget = targetPkg != null,
                    recording = recording,
                    starting = starting,
                    onRecord = ::startRecording,
                    onInspect = ::startInspect,
                )
            }
            item(key = "recent") {
                HomeRecentSessionsCard(
                    sessions = sessions,
                    loading = sessionsLoading,
                    now = now,
                    onOpenSession = onOpenSession,
                    onOpenSessions = onOpenSessions,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeServiceStatusCard(
    state: ServiceState,
    enabled: Boolean?,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOverlayChange: (Boolean) -> Unit,
) {
    HomeSectionCard(title = "Status layanan", icon = Icons.Outlined.Accessibility) {
        when {
            state.connected -> {
                val green = if (isSystemInDarkTheme()) HomeGreenDark else HomeGreenLight
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = green)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Layanan terhubung",
                            style = MaterialTheme.typography.titleSmall,
                            color = green,
                        )
                        Text(
                            text = "UI Mapper siap mengamati aplikasi yang Anda buka.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .toggleable(
                            value = state.overlayVisible,
                            role = Role.Switch,
                            onValueChange = onOverlayChange,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Tampilkan gelembung inspektur", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Tombol melayang untuk merekam, menangkap layar, dan inspeksi elemen di atas aplikasi lain.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = state.overlayVisible, onCheckedChange = null)
                }
            }

            enabled == true -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Layanan aktif, menunggu terhubung...", style = MaterialTheme.typography.titleSmall)
                }
                Text(
                    text = "Jika status ini tidak berubah dalam beberapa detik, matikan lalu nyalakan lagi " +
                        "UI Mapper di Pengaturan Aksesibilitas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onOpenAccessibility) { Text("Buka Pengaturan Aksesibilitas") }
            }

            enabled == null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Memeriksa status layanan...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Layanan aksesibilitas belum aktif",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "UI Mapper memerlukan akses aksesibilitas untuk membaca struktur tampilan aplikasi " +
                        "yang Anda petakan: elemen, teks tombol, ID, dan posisinya. Semua data tetap di " +
                        "perangkat ini. Secara bawaan UI Mapper hanya mengamati; ia hanya dapat " +
                        "mengisi teks kolom input bila Anda mengaktifkan \"edit teks\" di Pengaturan.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Di Pengaturan Aksesibilitas, pilih \"UI Mapper – Inspektur UI\" (biasanya di bagian " +
                        "Aplikasi terinstal / Aplikasi yang didownload), lalu aktifkan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text("Buka Pengaturan Aksesibilitas")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Outlined.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Android 13 ke atas: jika tombol aktifkan berwarna abu-abu " +
                                        "(\"setelan terbatas\"), buka Info Aplikasi, ketuk menu ⋮ di pojok kanan " +
                                        "atas, pilih \"Izinkan setelan terbatas\", lalu kembali ke Pengaturan " +
                                        "Aksesibilitas.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedButton(onClick = onOpenAppInfo) { Text("Buka Info Aplikasi") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeRecordingCard(
    state: ServiceState,
    session: Session?,
    onStop: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenLog: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(HomeRecordRed, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Perekaman aktif",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            val appName = session?.appLabel ?: session?.targetPkg ?: state.targetPkg ?: "Semua aplikasi"
            val activity = state.foregroundActivity?.let(::homeShortClassName)
            val screen = state.currentScreenId?.let { id ->
                val label = session?.screen(id)?.label
                if (label.isNullOrBlank() || label == id) id else "$id · $label"
            }

            HomeInfoRow("Aplikasi", appName)
            HomeInfoRow("Activity saat ini", activity ?: "-")
            HomeInfoRow("Layar saat ini", screen ?: "-")
            HomeInfoRow("Terpetakan", "${state.screenCount} layar · ${state.edgeCount} rute")
            state.status?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Hentikan")
                }
                OutlinedButton(
                    onClick = onOpenMap,
                    enabled = state.sessionId != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Lihat peta")
                }
            }

            TextButton(onClick = onOpenLog, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Lihat log")
            }
        }
    }
}

@Composable
private fun HomeTargetCard(pkg: String?, label: String?, onPickApp: () -> Unit) {
    HomeSectionCard(title = "Aplikasi yang dipetakan", icon = Icons.Outlined.Apps) {
        if (pkg == null) {
            Text(
                text = "Belum ada aplikasi yang dipilih. Pilih aplikasi yang ingin dipetakan UI dan rute " +
                    "navigasinya.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Pilih aplikasi")
            }
        } else {
            val icon = rememberHomeAppIcon(pkg, 48.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HomeAppIconBox(icon?.bitmap, 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label?.takeIf { it.isNotBlank() } ?: pkg,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (icon != null && !icon.installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Aplikasi ini tidak ditemukan di perangkat. Pilih aplikasi lain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            FilledTonalButton(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Pilih aplikasi")
            }
        }
    }
}

@Composable
private fun HomeStartCard(
    connected: Boolean,
    hasTarget: Boolean,
    recording: Boolean,
    starting: Boolean,
    onRecord: () -> Unit,
    onInspect: () -> Unit,
) {
    HomeSectionCard(title = "Mulai", icon = Icons.Outlined.PlayArrow) {
        val ready = connected && hasTarget
        if (!ready) {
            val reason = when {
                !connected && !hasTarget ->
                    "Aktifkan layanan aksesibilitas dan pilih aplikasi terlebih dahulu."
                !connected -> "Aktifkan dan hubungkan layanan aksesibilitas terlebih dahulu."
                else -> "Pilih aplikasi yang akan dipetakan terlebih dahulu."
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = onRecord,
            enabled = ready && !recording && !starting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(
                    Icons.Outlined.FiberManualRecord,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
            }
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Rekam rute navigasi")
        }
        Text(
            text = if (recording) {
                "Perekaman sedang berjalan. Hentikan dulu sebelum memulai sesi baru."
            } else {
                "Aplikasi target akan terbuka. Gunakan seperti biasa: setiap perpindahan layar dan tombol " +
                    "yang Anda ketuk akan dipetakan. Hentikan dari gelembung atau dari layar ini."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        FilledTonalButton(
            onClick = onInspect,
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.TouchApp, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text("Inspeksi elemen")
        }
        Text(
            text = "Gelembung inspektur muncul lalu aplikasi target dibuka. Ketuk gelembung, pilih " +
                "\"Inspeksi elemen\", lalu ketuk elemen mana pun untuk melihat propertinya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeRecentSessionsCard(
    sessions: List<Session>,
    loading: Boolean,
    now: Long,
    onOpenSession: (String) -> Unit,
    onOpenSessions: () -> Unit,
) {
    HomeSectionCard(
        title = "Sesi terbaru",
        icon = Icons.Outlined.History,
        actionLabel = if (sessions.isNotEmpty()) "Lihat semua" else null,
        onAction = onOpenSessions,
    ) {
        if (sessions.isEmpty() && loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Memuat sesi…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (sessions.isEmpty()) {
            Text(
                text = "Belum ada sesi. Pilih aplikasi lalu mulai merekam rute navigasi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val recent = sessions.take(HOME_RECENT_LIMIT)
            Column {
                recent.forEachIndexed { index, s ->
                    if (index > 0) HorizontalDivider()
                    HomeSessionRow(session = s, now = now, onClick = { onOpenSession(s.id) })
                }
            }
        }
    }
}

@Composable
private fun HomeSessionRow(session: Session, now: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = session.name.ifBlank { session.id },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val app = session.appLabel ?: session.targetPkg ?: "Berbagai aplikasi"
            Text(
                text = "$app · ${session.screens.size} layar · ${session.edges.size} rute",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val time = homeRelativeTime(session.updatedAt, now)
            if (time.isNotEmpty()) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

@Composable
private fun HomeSectionCard(
    title: String,
    icon: ImageVector,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    modifier = Modifier.weight(1f),
                )
                if (actionLabel != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            content()
        }
    }
}

@Composable
private fun HomeInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeAppIconBox(bitmap: ImageBitmap?, size: Dp) {
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Loads the launcher icon of [pkg] on Dispatchers.IO; null while loading. */
@Composable
private fun rememberHomeAppIcon(pkg: String, size: Dp): HomeIconResult? {
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // Lint false positive (compose-runtime 1.7): the keyed produceState overloads are always
    // flagged even though the producer assigns `value` below.
    @android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
    val result by produceState<HomeIconResult?>(initialValue = null, pkg, px) {
        value = null
        value = withContext(Dispatchers.IO) {
            val drawable = runCatching { AppInfo.icon(context, pkg) }.getOrNull()
            val bitmap = drawable?.let { d ->
                runCatching { d.toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap() }.getOrNull()
            }
            HomeIconResult(bitmap = bitmap, installed = drawable != null)
        }
    }
    return result
}

@Composable
private fun homeContentPadding(inner: PaddingValues, extra: Dp): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = inner.calculateStartPadding(direction) + extra,
        top = inner.calculateTopPadding() + extra,
        end = inner.calculateEndPadding(direction) + extra,
        bottom = inner.calculateBottomPadding() + extra,
    )
}

private fun homeShortClassName(cls: String): String = cls.substringAfterLast('.').ifBlank { cls }

/** Indonesian relative time: "baru saja", "5 menit lalu", "kemarin", "3 hari lalu" or a date. */
private fun homeRelativeTime(then: Long, now: Long): String {
    if (then <= 0L) return ""
    val minutes = (now - then).coerceAtLeast(0L) / 60_000L
    if (minutes < 1L) return "baru saja"
    if (minutes < 60L) return "$minutes menit lalu"
    val zone = ZoneId.systemDefault()
    val thenDate = Instant.ofEpochMilli(then).atZone(zone).toLocalDate()
    val nowDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(thenDate, nowDate)
    return when {
        days <= 0L -> "${minutes / 60L} jam lalu"
        days == 1L -> "kemarin"
        days < 7L -> "$days hari lalu"
        else -> HomeDateFormat.format(thenDate)
    }
}
