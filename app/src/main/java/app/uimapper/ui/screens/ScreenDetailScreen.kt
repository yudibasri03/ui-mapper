@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.uimapper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.uimapper.core.RouteFinder
import app.uimapper.core.UiTree
import app.uimapper.data.SessionStore
import app.uimapper.model.ActionType
import app.uimapper.model.NavEdge
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.ScreenSummary
import app.uimapper.model.Session
import app.uimapper.model.UiNode
import app.uimapper.ui.components.ConfirmDialog
import app.uimapper.ui.components.EdgeRow
import app.uimapper.ui.components.EmptyState
import app.uimapper.ui.components.InfoText
import app.uimapper.ui.components.InspectorActionableColor
import app.uimapper.ui.components.LoadingBox
import app.uimapper.ui.components.NodeTree
import app.uimapper.ui.components.NotFoundContent
import app.uimapper.ui.components.RenameDialog
import app.uimapper.ui.components.RouteSteps
import app.uimapper.ui.components.ScreenshotWithBounds
import app.uimapper.ui.components.SectionHeader
import app.uimapper.ui.components.TagChip
import app.uimapper.ui.components.isScreenNode
import app.uimapper.ui.components.rememberScreenshot
import app.uimapper.ui.components.storeWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SCREEN_DETAIL_TABS = listOf("Properti", "Hierarki", "Elemen", "Rute")

// =============================================================================================
// Loading
// =============================================================================================

/** Snapshot plus everything derived from it once, off the main thread. */
private class ScreenDetailData(
    val snapshot: ScreenSnapshot,
    /** Pre-order flattened tree. */
    val flat: List<UiNode>,
    /** Actionable nodes in reading order. */
    val actionable: List<UiNode>,
    /** idx -> ElementRef.key for every actionable node (to match recorded edges). */
    val elementKeys: Map<Int, String>,
    /** idx -> human-readable label for every actionable node. */
    val labels: Map<Int, String>,
    val screenW: Int,
    val screenH: Int,
) {
    private val byIdx: Map<Int, UiNode> = flat.associateBy { it.idx }

    fun node(idx: Int?): UiNode? = if (idx == null) null else byIdx[idx]
}

private sealed interface ScreenDetailLoad {
    data object Loading : ScreenDetailLoad
    data object Missing : ScreenDetailLoad
    class Ready(val data: ScreenDetailData) : ScreenDetailLoad
}

private fun prepareScreenDetail(snap: ScreenSnapshot): ScreenDetailData {
    val root = snap.root
    val flat = UiTree.flatten(root)
    val actionable = UiTree.actionable(root)
    val keys = HashMap<Int, String>(actionable.size * 2)
    val labels = HashMap<Int, String>(actionable.size * 2)
    for (n in actionable) {
        val ref = UiTree.toElementRef(root, n)
        keys[n.idx] = ref.key
        labels[n.idx] = ref.label ?: n.resIdEntry ?: n.simpleCls
    }
    val w = if (snap.screenW > 0) snap.screenW else (flat.maxOfOrNull { it.bounds.r } ?: 1).coerceAtLeast(1)
    val h = if (snap.screenH > 0) snap.screenH else (flat.maxOfOrNull { it.bounds.b } ?: 1).coerceAtLeast(1)
    return ScreenDetailData(snap, flat, actionable, keys, labels, w, h)
}

// =============================================================================================
// Screen
// =============================================================================================

/** Inspector for one captured screen: screenshot / wireframe with bounds, properties, tree, elements, routes. */
@Composable
fun ScreenDetailScreen(sessionId: String, screenId: String, onOpenScreen: (String) -> Unit, onBack: () -> Unit) {
    val sessions by SessionStore.sessions.collectAsStateWithLifecycle()
    val session = remember(sessions, sessionId) { sessions.firstOrNull { it.id == sessionId } }
    val summary = session?.screen(screenId)
    // Keyed on the screenshot too: attaching the first screenshot replaces the stored tree with the capture
    // the image belongs to, so it must be re-read.
    val shotKey = summary?.screenshot
    // Lint false positive (compose-runtime 1.7): the keyed produceState overloads are always
    // flagged even though the producer assigns `value` below.
    @android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
    val load by produceState<ScreenDetailLoad>(ScreenDetailLoad.Loading, sessionId, screenId, shotKey) {
        value = withContext(Dispatchers.IO) {
            val snap = SessionStore.loadScreen(sessionId, screenId)
            if (snap == null) {
                ScreenDetailLoad.Missing
            } else {
                ScreenDetailLoad.Ready(withContext(Dispatchers.Default) { prepareScreenDetail(snap) })
            }
        }
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val showMessage: (String) -> Unit = { message -> scope.launch { snackbar.showSnackbar(message) } }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val current = load
    val data = (current as? ScreenDetailLoad.Ready)?.data
    val label = summary?.label ?: data?.snapshot?.label.orEmpty()
    val title = if (label.isBlank()) screenId else "$screenId · $label"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    if (summary != null && !deleting) {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Ganti nama layar")
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Hapus layar")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                deleting -> LoadingBox(message = "Menghapus layar…")
                current is ScreenDetailLoad.Loading -> LoadingBox(message = "Memuat layar…")
                data == null -> NotFoundContent(
                    message = "Layar $screenId tidak ditemukan di sesi ini. Mungkin sudah dihapus.",
                    onBack = onBack,
                )
                else -> ScreenDetailContent(
                    sessionId = sessionId,
                    screenId = screenId,
                    title = title,
                    session = session,
                    summary = summary,
                    data = data,
                    onOpenScreen = onOpenScreen,
                    showMessage = showMessage,
                )
            }
        }
    }

    if (showRename && summary != null) {
        RenameDialog(
            title = "Ganti nama layar",
            initial = summary.label,
            fieldLabel = "Label layar",
            onDismiss = { showRename = false },
            onConfirm = { name ->
                showRename = false
                scope.launch {
                    storeWrite("Label tidak tersimpan", showMessage) {
                        SessionStore.updateScreenLabel(sessionId, screenId, name)
                    }
                }
            },
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = "Hapus layar $screenId?",
            message = "Layar ini, tangkapan layarnya, dan semua transisi yang terhubung dengannya akan dihapus dari sesi.",
            confirmLabel = "Hapus",
            onConfirm = {
                showDelete = false
                deleting = true
                scope.launch {
                    val ok = storeWrite("Layar tidak terhapus", showMessage) {
                        SessionStore.deleteScreen(sessionId, screenId)
                    }
                    if (ok) onBack() else deleting = false
                }
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun ScreenDetailContent(
    sessionId: String,
    screenId: String,
    title: String,
    session: Session?,
    summary: ScreenSummary?,
    data: ScreenDetailData,
    onOpenScreen: (String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedIdx by rememberSaveable(sessionId, screenId) { mutableStateOf<Int?>(null) }
    var onlyClickable by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val selected = remember(data, selectedIdx) { data.node(selectedIdx) }
    val shot = rememberScreenshot(sessionId, screenId, summary?.screenshot ?: data.snapshot.screenshot, targetWidth = 1080)
    val onSelect: (UiNode) -> Unit = { selectedIdx = it.idx }

    val onTap: (Int, Int) -> Unit = { x, y ->
        val only = onlyClickable
        scope.launch {
            val hit = withContext(Dispatchers.Default) {
                if (only) {
                    UiTree.hitTestAll(data.snapshot.root, x, y).firstOrNull { it.isActionable }
                } else {
                    UiTree.hitTest(data.snapshot.root, x, y)
                }
            }
            if (hit != null) {
                selectedIdx = hit.idx
            } else if (only) {
                showMessage("Tidak ada elemen yang bisa diklik di titik itu")
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                ScreenPreviewPane(
                    data = data,
                    shot = shot,
                    selected = selected,
                    onlyClickable = onlyClickable,
                    onToggleOnlyClickable = { onlyClickable = !onlyClickable },
                    onTap = onTap,
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                )
                VerticalDivider()
                ScreenTabsPane(
                    tab = tab,
                    onTab = { tab = it },
                    screenId = screenId,
                    title = title,
                    session = session,
                    data = data,
                    selected = selected,
                    onSelect = onSelect,
                    onOpenScreen = onOpenScreen,
                    showMessage = showMessage,
                    modifier = Modifier.weight(0.58f).fillMaxHeight(),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ScreenPreviewPane(
                    data = data,
                    shot = shot,
                    selected = selected,
                    onlyClickable = onlyClickable,
                    onToggleOnlyClickable = { onlyClickable = !onlyClickable },
                    onTap = onTap,
                    modifier = Modifier.fillMaxWidth().weight(0.45f),
                )
                HorizontalDivider()
                ScreenTabsPane(
                    tab = tab,
                    onTab = { tab = it },
                    screenId = screenId,
                    title = title,
                    session = session,
                    data = data,
                    selected = selected,
                    onSelect = onSelect,
                    onOpenScreen = onOpenScreen,
                    showMessage = showMessage,
                    modifier = Modifier.fillMaxWidth().weight(0.55f),
                )
            }
        }
    }
}

@Composable
private fun ScreenPreviewPane(
    data: ScreenDetailData,
    shot: ImageBitmap?,
    selected: UiNode?,
    onlyClickable: Boolean,
    onToggleOnlyClickable: () -> Unit,
    onTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val caption = remember(data, shot != null) {
        buildList {
            data.snapshot.activity?.substringAfterLast('.')?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("${data.flat.size} elemen")
            add("${data.actionable.size} bisa diklik")
            if (shot == null) add("wireframe (tanpa tangkapan layar)")
        }.joinToString(" · ")
    }
    Column(modifier.background(cs.surfaceContainerLow)) {
        ScreenshotWithBounds(
            screenshot = shot,
            screenW = data.screenW,
            screenH = data.screenH,
            nodes = data.flat,
            selected = selected,
            onlyClickable = onlyClickable,
            onTapScreen = onTap,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = onlyClickable,
                onClick = onToggleOnlyClickable,
                label = { Text("Hanya yang bisa diklik") },
                leadingIcon = if (onlyClickable) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ScreenTabsPane(
    tab: Int,
    onTab: (Int) -> Unit,
    screenId: String,
    title: String,
    session: Session?,
    data: ScreenDetailData,
    selected: UiNode?,
    onSelect: (UiNode) -> Unit,
    onOpenScreen: (String) -> Unit,
    showMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab) {
            SCREEN_DETAIL_TABS.forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(name, maxLines = 1) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> ScreenPropertiesTab(data, selected, title, onSelect, showMessage)
                1 -> NodeTree(
                    root = data.snapshot.root,
                    selectedIdx = selected?.idx,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxSize(),
                )
                2 -> ScreenElementsTab(session, screenId, data, selected?.idx, onSelect, onOpenScreen)
                else -> ScreenRoutesTab(session, screenId, onOpenScreen)
            }
        }
    }
}

// =============================================================================================
// Tab "Properti"
// =============================================================================================

private const val DASH = "—"

private fun nodeProperties(root: UiNode, n: UiNode): List<Pair<String, String>> {
    val textValue = when {
        n.password -> "(kolom kata sandi — isi tidak disimpan)"
        n.editable -> "(kolom input — isi tidak disimpan)"
        else -> n.text ?: DASH
    }
    return listOf(
        "Label" to (UiTree.labelOf(n) ?: DASH),
        "Kelas" to n.cls,
        "Resource-id" to (n.resId ?: DASH),
        "Teks" to textValue,
        "Content-desc" to (n.desc ?: DASH),
        "Hint" to (n.hint ?: DASH),
        "Pane title" to (n.paneTitle ?: DASH),
        "Paket" to (n.pkg ?: DASH),
        "Bounds" to n.bounds.toString(),
        "Ukuran" to "${n.bounds.width} × ${n.bounds.height} px",
        "Titik tengah" to "(${n.bounds.centerX}, ${n.bounds.centerY})",
        "Indeks (idx)" to n.idx.toString(),
        "Kedalaman" to n.depth.toString(),
        "Jumlah anak" to n.children.size.toString(),
        "Aksi" to n.actions.joinToString(", ").ifEmpty { DASH },
        "XPath" to UiTree.xpath(root, n.idx),
    )
}

private fun nodeFlags(n: UiNode): List<String> = buildList {
    add(if (n.enabled) "enabled" else "disabled")
    add(if (n.visible) "visible" else "tidak terlihat")
    if (n.clickable) add("clickable")
    if (n.longClickable) add("long-clickable")
    if (n.checkable) add("checkable")
    if (n.checked) add("checked")
    if (n.focusable) add("focusable")
    if (n.focused) add("focused")
    if (n.scrollable) add("scrollable")
    if (n.editable) add("editable")
    if (n.password) add("password")
    if (n.selected) add("selected")
    if (n.heading) add("heading")
}

private fun propertiesText(screenTitle: String, props: List<Pair<String, String>>, flags: List<String>): String =
    buildString {
        appendLine("Layar: $screenTitle")
        for ((k, v) in props) appendLine("$k: $v")
        append("Status: ").append(flags.joinToString(", "))
    }

private val MONO_PROPERTY_KEYS = setOf("Kelas", "Resource-id", "Bounds", "Aksi", "XPath")

@Composable
private fun ScreenPropertiesTab(
    data: ScreenDetailData,
    node: UiNode?,
    screenTitle: String,
    onSelect: (UiNode) -> Unit,
    showMessage: (String) -> Unit,
) {
    if (node == null) {
        EmptyState(
            icon = Icons.Filled.TouchApp,
            title = "Belum ada elemen dipilih",
            message = "Ketuk elemen pada gambar di atas, atau pilih dari tab Hierarki / Elemen.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val clipboard = LocalClipboardManager.current
    val root = data.snapshot.root
    val parent = remember(data, node.idx) { UiTree.parentOf(root, node.idx) }
    val props = remember(data, node) { nodeProperties(root, node) }
    val flags = remember(node) { nodeFlags(node) }
    val cs = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            props.first().second.takeIf { it != DASH } ?: node.simpleCls,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { parent?.let(onSelect) }, enabled = parent != null) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Induk")
            }
            OutlinedButton(
                onClick = { node.children.firstOrNull()?.let(onSelect) },
                enabled = node.children.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Anak pertama")
            }
            FilledTonalButton(onClick = {
                clipboard.setText(AnnotatedString(propertiesText(screenTitle, props, flags)))
                showMessage("Properti elemen disalin")
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Salin")
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            flags.forEach { flag ->
                val negative = flag == "disabled" || flag == "tidak terlihat"
                TagChip(
                    flag,
                    containerColor = if (negative) cs.errorContainer else cs.secondaryContainer,
                    contentColor = if (negative) cs.onErrorContainer else cs.onSecondaryContainer,
                )
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                props.forEach { (key, value) ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(key, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(
                            value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = if (key in MONO_PROPERTY_KEYS) FontFamily.Monospace else null,
                        )
                    }
                }
            }
        }
    }
}

// =============================================================================================
// Tab "Elemen"
// =============================================================================================

@Composable
private fun ScreenElementsTab(
    session: Session?,
    screenId: String,
    data: ScreenDetailData,
    selectedIdx: Int?,
    onSelect: (UiNode) -> Unit,
    onOpenScreen: (String) -> Unit,
) {
    val outgoing = remember(session, screenId) {
        session?.outgoing(screenId).orEmpty().filter { it.element != null }
    }
    val edgesByNode = remember(data, outgoing) {
        data.actionable.associate { n ->
            val key = data.elementKeys[n.idx]
            n.idx to outgoing.filter { e ->
                val el = e.element ?: return@filter false
                // An edge resolved against this stored tree names its node exactly; the key (shared by
                // elements with the same resource-id, e.g. list rows) is only for edges without one.
                val idx = el.nodeIdx
                if (idx != null) idx == n.idx else el.key == key
            }
        }
    }
    val withRoutes = remember(edgesByNode) { edgesByNode.count { it.value.isNotEmpty() } }

    if (data.actionable.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.TouchApp,
            title = "Tidak ada elemen yang bisa diklik",
            message = "Layar ini tidak memiliki elemen clickable / long-clickable yang terlihat.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            InfoText("${data.actionable.size} elemen bisa diklik · $withRoutes sudah punya rute tercatat")
        }
        items(data.actionable, key = { it.idx }) { n ->
            ScreenElementRow(
                node = n,
                label = data.labels[n.idx] ?: n.simpleCls,
                edges = edgesByNode[n.idx].orEmpty(),
                session = session,
                selected = n.idx == selectedIdx,
                onClick = { onSelect(n) },
                onOpenScreen = onOpenScreen,
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ScreenElementRow(
    node: UiNode,
    label: String,
    edges: List<NavEdge>,
    session: Session?,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenScreen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = null,
                tint = InspectorActionableColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (node.longClickable) {
                TagChip("tekan lama", modifier = Modifier.padding(start = 6.dp))
            }
            Text(
                "#${node.idx}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            listOfNotNull(node.resIdEntry?.let { "id/$it" }, node.simpleCls, node.bounds.toString()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (edges.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                edges.forEach { e ->
                    val targetIsScreen = session != null && isScreenNode(session, e.to)
                    val target = session?.let { RouteFinder.nodeLabel(it, e.to) } ?: e.to
                    val prefix = if (e.action == ActionType.CLICK) "" else RouteFinder.actionVerb(e.action) + " "
                    AssistChip(
                        onClick = { if (targetIsScreen) onOpenScreen(e.to) },
                        enabled = targetIsScreen,
                        label = { Text("$prefix→ $target", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.widthIn(max = 280.dp),
                    )
                }
            }
        } else {
            Text(
                "Belum ada rute tercatat dari elemen ini",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// =============================================================================================
// Tab "Rute"
// =============================================================================================

@Composable
private fun ScreenRoutesTab(session: Session?, screenId: String, onOpenScreen: (String) -> Unit) {
    if (session == null) {
        InfoText("Data sesi tidak tersedia.")
        return
    }
    val incoming = remember(session, screenId) { session.incoming(screenId) }
    val outgoing = remember(session, screenId) { session.outgoing(screenId) }
    val startId = remember(session) { RouteFinder.startScreenId(session) }
    val route = remember(session, screenId, startId) {
        startId?.let { RouteFinder.shortestPath(session, it, screenId) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "h-start") { SectionHeader(title = "Rute dari layar awal") }
        item(key = "start") {
            when {
                startId == null -> InfoText("Belum ada layar awal tercatat.")
                startId == screenId -> InfoText("Ini adalah layar awal (titik masuk aplikasi).")
                route == null -> InfoText(
                    "Tidak ada rute tercatat dari ${RouteFinder.nodeLabel(session, startId)} ke layar ini.",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(
                        "${route.size} langkah dari ${RouteFinder.nodeLabel(session, startId)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    RouteSteps(session, route, onOpenScreen)
                }
            }
        }

        item(key = "h-in") { SectionHeader(title = "Masuk ke layar ini", count = incoming.size) }
        if (incoming.isEmpty()) {
            item(key = "in-empty") { InfoText("Belum ada transisi masuk.") }
        } else {
            items(incoming, key = { "in-" + it.id }) { edge ->
                EdgeRow(
                    session = session,
                    edge = edge,
                    onClick = if (isScreenNode(session, edge.from) && edge.from != screenId) {
                        { onOpenScreen(edge.from) }
                    } else {
                        null
                    },
                )
                HorizontalDivider()
            }
        }

        item(key = "h-out") { SectionHeader(title = "Keluar dari layar ini", count = outgoing.size) }
        if (outgoing.isEmpty()) {
            item(key = "out-empty") { InfoText("Belum ada transisi keluar.") }
        } else {
            items(outgoing, key = { "out-" + it.id }) { edge ->
                EdgeRow(
                    session = session,
                    edge = edge,
                    onClick = if (isScreenNode(session, edge.to) && edge.to != screenId) {
                        { onOpenScreen(edge.to) }
                    } else {
                        null
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
