@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.uimapper.ui.screens

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Schema
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.uimapper.core.RouteFinder
import app.uimapper.data.SessionStore
import app.uimapper.export.ExportFormat
import app.uimapper.export.Exporters
import app.uimapper.model.NavEdge
import app.uimapper.model.ScreenSummary
import app.uimapper.model.Session
import app.uimapper.service.ServiceBridge
import app.uimapper.service.ServiceMode
import app.uimapper.service.ServiceState
import app.uimapper.ui.components.ConfirmDialog
import app.uimapper.ui.components.EdgeRow
import app.uimapper.ui.components.EmptyState
import app.uimapper.ui.components.GraphView
import app.uimapper.ui.components.InfoText
import app.uimapper.ui.components.NotFoundContent
import app.uimapper.ui.components.RenameDialog
import app.uimapper.ui.components.RouteSteps
import app.uimapper.ui.components.SectionHeader
import app.uimapper.ui.components.TagChip
import app.uimapper.ui.components.formatDateTime
import app.uimapper.ui.components.isScreenNode
import app.uimapper.ui.components.rememberScreenshot
import app.uimapper.ui.components.storeWrite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SESSION_DETAIL_TABS = listOf("Layar", "Peta", "Rute", "Ekspor")

/** One session: its screens, navigation map, routes and export actions. Updates live while recording. */
@Composable
fun SessionDetailScreen(sessionId: String, onOpenScreen: (String) -> Unit, onBack: () -> Unit) {
    val sessions by SessionStore.sessions.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }
    val service by ServiceBridge.state.collectAsStateWithLifecycle()
    val recording = service.mode == ServiceMode.RECORDING && service.sessionId == sessionId
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showRename by remember { mutableStateOf(false) }

    // Exports run in a ViewModel of this back-stack entry: they survive rotation and tab switches, and
    // their result is delivered here whichever tab is open.
    val exportVm: SessionExportViewModel = viewModel()
    val exportBusy by exportVm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(exportVm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            exportVm.events.collect { event ->
                when (event) {
                    is SessionExportViewModel.Event.Share -> try {
                        Exporters.share(context, event.file, event.mime)
                    } catch (e: Exception) {
                        showMessage("Ekspor tidak dapat dibagikan: ${errorText(e)}")
                    }
                    is SessionExportViewModel.Event.Message -> showMessage(event.text)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.name ?: "Sesi", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (session != null) {
                            Text(
                                sessionSubtitle(session),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (session != null) {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Ganti nama sesi")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (session == null) {
            NotFoundContent(
                message = "Sesi ini tidak ditemukan. Mungkin sudah dihapus.",
                onBack = onBack,
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (recording) SessionRecordingBanner(service)
                PrimaryTabRow(selectedTabIndex = tab) {
                    SESSION_DETAIL_TABS.forEachIndexed { i, title ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1) })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> SessionScreensTab(session, onOpenScreen)
                        1 -> GraphView(session = session, onOpenScreen = onOpenScreen, modifier = Modifier.fillMaxSize())
                        2 -> SessionRoutesTab(session, onOpenScreen, showMessage)
                        else -> SessionExportTab(
                            session = session,
                            exportBusy = exportBusy,
                            onShare = { format -> exportVm.share(session.id, format) },
                            onSave = { format -> exportVm.save(session.id, format) },
                            showMessage = showMessage,
                        )
                    }
                }
            }
        }
    }

    if (showRename && session != null) {
        RenameDialog(
            title = "Ganti nama sesi",
            initial = session.name,
            onDismiss = { showRename = false },
            onConfirm = { name ->
                showRename = false
                scope.launch {
                    storeWrite("Nama tidak tersimpan", showMessage) { SessionStore.rename(session.id, name) }
                }
            },
        )
    }
}

private fun sessionSubtitle(session: Session): String {
    val app = session.appLabel ?: session.targetPkg ?: "Aplikasi tidak diketahui"
    val pkg = session.targetPkg
    return if (pkg != null && pkg != app) "$app · $pkg" else app
}

@Composable
private fun SessionRecordingBanner(state: ServiceState) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.errorContainer, contentColor = cs.onErrorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                state.status ?: "Sedang merekam — layar dan rute baru muncul otomatis.",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// =============================================================================================
// Tab "Layar"
// =============================================================================================

@Composable
private fun SessionScreensTab(session: Session, onOpenScreen: (String) -> Unit) {
    if (session.screens.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Smartphone,
            title = "Belum ada layar",
            message = "Mulai rekam dari beranda lalu jelajahi aplikasi target. Setiap layar baru yang " +
                "terdeteksi akan muncul di sini.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val startId = remember(session) { RouteFinder.startScreenId(session) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(session.screens, key = { it.id }) { screen ->
            SessionScreenCard(
                sessionId = session.id,
                screen = screen,
                isStart = screen.id == startId,
                onClick = { onOpenScreen(screen.id) },
            )
        }
    }
}

@Composable
private fun SessionScreenCard(sessionId: String, screen: ScreenSummary, isStart: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val thumb = rememberScreenshot(sessionId, screen.id, screen.screenshot, targetWidth = 300)
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(180.dp).background(cs.surfaceVariant)) {
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = "Tangkapan layar ${screen.id}",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Smartphone,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = cs.onSurfaceVariant,
                    )
                    if (screen.screenshot == null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tanpa tangkapan layar",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            TagChip(
                screen.id,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
            )
            if (isStart) {
                TagChip(
                    "Awal",
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    containerColor = cs.tertiary,
                    contentColor = cs.onTertiary,
                    icon = Icons.Filled.Flag,
                )
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                screen.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            screen.activity?.let {
                Text(
                    it.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${screen.nodeCount} elemen · ${screen.clickableCount} bisa diklik",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                "Dikunjungi ${screen.visits}×",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

// =============================================================================================
// Tab "Rute"
// =============================================================================================

@Composable
private fun SessionRoutesTab(session: Session, onOpenScreen: (String) -> Unit, showMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val startId = remember(session) { RouteFinder.startScreenId(session) }
    val routes = remember(session) { RouteFinder.routesFromStart(session) }

    var fromId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var toId by rememberSaveable(session.id) { mutableStateOf<String?>(null) }
    var includeBack by rememberSaveable { mutableStateOf(false) }
    val effectiveFrom = fromId?.takeIf { isScreenNode(session, it) } ?: startId
    val effectiveTo = toId?.takeIf { isScreenNode(session, it) }
    val found = remember(session, effectiveFrom, effectiveTo, includeBack) {
        if (effectiveFrom != null && effectiveTo != null) {
            RouteFinder.shortestPath(session, effectiveFrom, effectiveTo, includeBack)
        } else {
            null
        }
    }

    var openStart by rememberSaveable { mutableStateOf(true) }
    var openFinder by rememberSaveable { mutableStateOf(true) }
    var openAll by rememberSaveable { mutableStateOf(true) }
    var edgeToDelete by remember { mutableStateOf<NavEdge?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        // (1) Routes from the start screen
        item(key = "h-start") {
            SectionHeader(
                title = "Rute dari layar awal",
                count = session.screens.size,
                expanded = openStart,
                onToggle = { openStart = !openStart },
            )
        }
        if (openStart) {
            if (startId == null || session.screens.isEmpty()) {
                item(key = "start-empty") { InfoText("Belum ada layar tercatat.") }
            } else {
                item(key = "start-info") {
                    InfoText("Layar awal: ${RouteFinder.nodeLabel(session, startId)}. Langkah di bawah adalah rute terpendek yang pernah tercatat; \"Transisi\" adalah perpindahan tanpa ketukan (mis. splash).")
                }
                items(session.screens, key = { "r-" + it.id }) { screen ->
                    SessionStartRouteItem(session, screen, startId, routes[screen.id], onOpenScreen)
                }
            }
        }

        // (2) Route finder
        item(key = "h-finder") {
            SectionHeader(title = "Pencari rute", expanded = openFinder, onToggle = { openFinder = !openFinder })
        }
        if (openFinder) {
            item(key = "finder") {
                SessionRouteFinder(
                    session = session,
                    fromId = effectiveFrom,
                    toId = effectiveTo,
                    includeBack = includeBack,
                    result = found,
                    onFrom = { fromId = it },
                    onTo = { toId = it },
                    onSwap = {
                        val f = effectiveFrom
                        fromId = effectiveTo
                        toId = f
                    },
                    onIncludeBack = { includeBack = it },
                    onOpenScreen = onOpenScreen,
                )
            }
        }

        // (3) Every recorded transition
        item(key = "h-all") {
            SectionHeader(
                title = "Semua transisi",
                count = session.edges.size,
                expanded = openAll,
                onToggle = { openAll = !openAll },
            )
        }
        if (openAll) {
            if (session.edges.isEmpty()) {
                item(key = "all-empty") { InfoText("Belum ada transisi tercatat.") }
            } else {
                item(key = "all-hint") {
                    InfoText("Ketuk untuk membuka layar tujuan. Tekan lama atau ikon hapus untuk menghapus transisi.")
                }
                items(session.edges, key = { "e-" + it.id }) { edge ->
                    EdgeRow(
                        session = session,
                        edge = edge,
                        onClick = if (isScreenNode(session, edge.to)) {
                            { onOpenScreen(edge.to) }
                        } else {
                            null
                        },
                        onLongClick = { edgeToDelete = edge },
                        onDelete = { edgeToDelete = edge },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    edgeToDelete?.let { edge ->
        ConfirmDialog(
            title = "Hapus transisi?",
            message = RouteFinder.describe(session, edge) +
                "\n\nTercatat ${edge.count}× (terakhir ${formatDateTime(edge.lastAt)}).",
            confirmLabel = "Hapus",
            onConfirm = {
                edgeToDelete = null
                scope.launch {
                    val ok = storeWrite("Transisi tidak terhapus", showMessage) {
                        SessionStore.deleteEdge(session.id, edge.id)
                    }
                    if (ok) showMessage("Transisi dihapus")
                }
            },
            onDismiss = { edgeToDelete = null },
        )
    }
}

@Composable
private fun SessionStartRouteItem(
    session: Session,
    screen: ScreenSummary,
    startId: String,
    route: List<NavEdge>?,
    onOpenScreen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "${screen.id} · ${screen.label}",
            style = MaterialTheme.typography.titleSmall,
            color = cs.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onOpenScreen(screen.id) }.padding(vertical = 4.dp),
        )
        when {
            screen.id == startId -> Text(
                "Layar awal — titik masuk aplikasi.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            route == null -> Text(
                "Tidak ada rute tercatat dari layar awal.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
            )
            else -> {
                Text(
                    "${route.size} langkah",
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                )
                RouteSteps(session, route, onOpenScreen)
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

@Composable
private fun SessionRouteFinder(
    session: Session,
    fromId: String?,
    toId: String?,
    includeBack: Boolean,
    result: List<NavEdge>?,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onSwap: () -> Unit,
    onIncludeBack: (Boolean) -> Unit,
    onOpenScreen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    if (session.screens.size < 2) {
        InfoText("Butuh minimal dua layar untuk mencari rute.")
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionScreenDropdown(label = "Dari", session = session, selectedId = fromId, onSelect = onFrom)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionScreenDropdown(
                label = "Ke",
                session = session,
                selectedId = toId,
                onSelect = onTo,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSwap) {
                Icon(Icons.Filled.SwapVert, contentDescription = "Tukar asal dan tujuan")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = includeBack, onCheckedChange = onIncludeBack)
            Spacer(Modifier.width(12.dp))
            Text("Sertakan langkah Kembali", style = MaterialTheme.typography.bodyMedium)
        }
        when {
            fromId == null || toId == null -> Text(
                "Pilih layar asal dan tujuan.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            fromId == toId -> Text(
                "Layar asal dan tujuan sama.",
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            result == null -> Text(
                "Tidak ada rute tercatat",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.error,
            )
            else -> {
                Text(
                    "${result.size} langkah",
                    style = MaterialTheme.typography.labelLarge,
                    color = cs.primary,
                )
                RouteSteps(session, result, onOpenScreen)
            }
        }
    }
}

@Composable
private fun SessionScreenDropdown(
    label: String,
    session: Session,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selectedId?.let { RouteFinder.nodeLabel(session, it) }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text("Pilih layar") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            session.screens.forEach { screen ->
                DropdownMenuItem(
                    text = {
                        Text("${screen.id} · ${screen.label}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    onClick = {
                        onSelect(screen.id)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

// =============================================================================================
// Tab "Ekspor"
// =============================================================================================

private fun exportDescription(name: String): String = when (name) {
    "JSON" -> "Data lengkap sesi: layar, pohon elemen, dan semua transisi. Cocok untuk diolah ulang."
    "MERMAID" -> "Diagram alur Mermaid (flowchart) untuk Markdown, GitHub, GitLab, atau Notion."
    "DOT" -> "Graphviz DOT untuk dirender menjadi diagram navigasi (SVG/PNG)."
    "CSV" -> "Tabel semua elemen tiap layar (kelas, resource-id, teks, status, bounds, XPath) untuk spreadsheet."
    "HTML" -> "Laporan mandiri yang bisa dibuka di browser mana pun."
    "ZIP" -> "Satu arsip berisi JSON, Mermaid, DOT, CSV elemen & rute, laporan HTML, dan tangkapan layar."
    else -> "Ekspor sesi dalam format ini."
}

private fun exportIcon(name: String): ImageVector = when (name) {
    "JSON" -> Icons.Filled.Code
    "MERMAID" -> Icons.Filled.AccountTree
    "DOT" -> Icons.Filled.Schema
    "CSV" -> Icons.Filled.TableChart
    "HTML" -> Icons.Filled.Web
    "ZIP" -> Icons.Filled.FolderZip
    else -> Icons.Filled.Description
}

private fun errorText(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

@Composable
private fun SessionExportTab(
    session: Session,
    /** "share:<FORMAT>" or "save:<FORMAT>" while an export of [SessionExportViewModel] runs. */
    exportBusy: String?,
    onShare: (ExportFormat) -> Unit,
    onSave: (ExportFormat) -> Unit,
    showMessage: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copyingMermaid by remember { mutableStateOf(false) }
    /** "share:<FORMAT>", "save:<FORMAT>" or "mermaid" while a job runs. */
    val busy = exportBusy ?: if (copyingMermaid) "mermaid" else null

    fun share(format: ExportFormat) {
        if (busy != null) return
        onShare(format)
    }

    fun save(format: ExportFormat) {
        if (busy != null) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showMessage("Simpan ke Unduhan butuh Android 10 atau lebih baru. Gunakan Bagikan.")
            return
        }
        onSave(format)
    }

    fun copyMermaid() {
        if (busy != null) return
        copyingMermaid = true
        scope.launch {
            try {
                val code = withContext(Dispatchers.Default) { Exporters.mermaid(session) }
                clipboard.setText(AnnotatedString(code))
                showMessage("Kode Mermaid disalin ke papan klip")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Gagal menyalin Mermaid: ${errorText(e)}")
            } finally {
                copyingMermaid = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Ekspor peta UI dan rute navigasi: ${session.screens.size} layar, ${session.edges.size} transisi.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ExportFormat.entries.forEach { format ->
            SessionExportCard(
                format = format,
                busy = busy,
                onShare = { share(format) },
                onSave = { save(format) },
            )
        }
        OutlinedButton(onClick = { copyMermaid() }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            if (busy == "mermaid") {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text("Salin kode Mermaid")
        }
        Text(
            "Semua berkas dibuat di perangkat ini; tidak ada data yang dikirim ke internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SessionExportCard(format: ExportFormat, busy: String?, onShare: () -> Unit, onSave: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sharing = busy == "share:${format.name}"
    val saving = busy == "save:${format.name}"
    val idle = busy == null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(exportIcon(format.name), contentDescription = null, tint = cs.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(format.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "." + format.ext.removePrefix("."),
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                exportDescription(format.name),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onShare, enabled = idle) {
                    if (sharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Bagikan")
                }
                OutlinedButton(onClick = onSave, enabled = idle) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    } else {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Simpan ke Unduhan")
                }
            }
        }
    }
}
