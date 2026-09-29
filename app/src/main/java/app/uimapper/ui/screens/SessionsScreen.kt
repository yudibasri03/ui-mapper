@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.uimapper.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.uimapper.data.SessionStore
import app.uimapper.model.Session
import app.uimapper.service.ServiceBridge
import app.uimapper.service.ServiceCommand
import app.uimapper.service.ServiceMode
import app.uimapper.ui.components.ConfirmDialog
import app.uimapper.ui.components.EmptyState
import app.uimapper.ui.components.LoadingBox
import app.uimapper.ui.components.ModeChip
import app.uimapper.ui.components.RenameDialog
import app.uimapper.ui.components.TagChip
import app.uimapper.ui.components.formatRelativeTime
import app.uimapper.ui.components.rememberNow
import app.uimapper.ui.components.storeWrite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** List of every mapping session stored on the device. */
@Composable
fun SessionsScreen(onOpenSession: (String) -> Unit, onBack: () -> Unit) {
    val sessions by SessionStore.sessions.collectAsStateWithLifecycle()
    val loading by SessionStore.loading.collectAsStateWithLifecycle()
    val service by ServiceBridge.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val now = rememberNow()
    var renameTarget by remember { mutableStateOf<Session?>(null) }
    var deleteTarget by remember { mutableStateOf<Session?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sesi pemetaan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sessions.isEmpty() && loading) {
            LoadingBox(modifier = Modifier.padding(padding).fillMaxSize(), message = "Memuat sesi…")
        } else if (sessions.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Inbox,
                title = "Belum ada sesi",
                message = "Pilih aplikasi di beranda, lalu mulai rekam atau ambil snapshot. Setiap sesi " +
                    "menyimpan peta layar dan rute tombol aplikasi tersebut.",
                modifier = Modifier.padding(padding).fillMaxSize(),
                action = { OutlinedButton(onClick = onBack) { Text("Kembali ke beranda") } },
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "summary") {
                    Text(
                        "${sessions.size} sesi · tersimpan hanya di perangkat ini",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(sessions, key = { it.id }) { s ->
                    SessionListCard(
                        session = s,
                        recording = service.mode == ServiceMode.RECORDING && service.sessionId == s.id,
                        now = now,
                        onOpen = { onOpenSession(s.id) },
                        onRename = { renameTarget = s },
                        onDelete = { deleteTarget = s },
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            title = "Ganti nama sesi",
            initial = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                scope.launch {
                    storeWrite("Nama tidak tersimpan", { msg -> scope.launch { snackbar.showSnackbar(msg) } }) {
                        SessionStore.rename(target.id, name)
                    }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        ConfirmDialog(
            title = "Hapus sesi?",
            message = "\"${target.name}\" beserta ${target.screens.size} layar, ${target.edges.size} rute, dan " +
                "semua tangkapan layarnya akan dihapus permanen dari perangkat ini.",
            confirmLabel = "Hapus",
            onConfirm = {
                deleteTarget = null
                scope.launch {
                    val st = ServiceBridge.state.value
                    if (st.mode == ServiceMode.RECORDING && st.sessionId == target.id) {
                        // Stop the recorder first so it does not keep writing into a deleted session.
                        ServiceBridge.send(ServiceCommand.Stop)
                    }
                    withContext(Dispatchers.IO) { SessionStore.delete(target.id) }
                    snackbar.showSnackbar("Sesi \"${target.name}\" dihapus")
                }
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun SessionListCard(
    session: Session,
    recording: Boolean,
    now: Long,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    session.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    session.appLabel ?: session.targetPkg ?: "Aplikasi tidak diketahui",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val pkg = session.targetPkg
                if (pkg != null && pkg != session.appLabel) {
                    Text(
                        pkg,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ModeChip(session.mode)
                    if (recording) {
                        TagChip(
                            "Merekam",
                            containerColor = cs.errorContainer,
                            contentColor = cs.onErrorContainer,
                            icon = Icons.Filled.FiberManualRecord,
                        )
                    }
                    TagChip(
                        "${session.screens.size} layar",
                        containerColor = cs.surfaceVariant,
                        contentColor = cs.onSurfaceVariant,
                    )
                    TagChip(
                        "${session.edges.size} rute",
                        containerColor = cs.surfaceVariant,
                        contentColor = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Diperbarui ${formatRelativeTime(session.updatedAt, now)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu sesi")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Ganti nama") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Hapus") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
