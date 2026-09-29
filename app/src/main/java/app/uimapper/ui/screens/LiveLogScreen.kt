package app.uimapper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.uimapper.export.Exporters
import app.uimapper.service.EventLog
import app.uimapper.service.ServiceBridge
import app.uimapper.service.ServiceMode
import app.uimapper.service.ServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

// Terminal palette. Fixed dark colours so the feed reads like a console in either app theme.
private val TerminalBg = Color(0xFF0B0F14)
private val TerminalBar = Color(0xFF10161D)
private val TerminalDivider = Color(0xFF1E2730)
private val TimeColor = Color(0xFF5A646E)
private val MessageColor = Color(0xFFC7D0DA)
private val BarContent = Color(0xFFD5DDE6)
private val DotIdle = Color(0xFF6B7681)
private val DotRecording = Color(0xFF57D364)

private const val TAG_WIDTH = 7 // widest tag name is "INSPECT"

private val LogTimeFormat: SimpleDateFormat get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private fun tagColor(tag: EventLog.Tag): Color = when (tag) {
    EventLog.Tag.REC, EventLog.Tag.EDGE -> Color(0xFF57D364) // green
    EventLog.Tag.NEW -> Color(0xFF4DD0E1) // cyan
    EventLog.Tag.SEEN -> Color(0xFF7A8794) // dim
    EventLog.Tag.TAP -> Color(0xFFE8C15A) // amber
    EventLog.Tag.BACK -> Color(0xFFF0A05A) // orange
    EventLog.Tag.EXT -> Color(0xFFD98CE0) // magenta
    EventLog.Tag.SNAP -> Color(0xFF6AA9FF) // blue
    EventLog.Tag.INSPECT -> Color(0xFF4FC7B8) // teal
    EventLog.Tag.STOP, EventLog.Tag.INFO -> Color(0xFF9AA5B1) // gray
    EventLog.Tag.WARN -> Color(0xFFFF6B6B) // red
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val entries by EventLog.entries.collectAsStateWithLifecycle()
    val state by ServiceBridge.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }

    // True when the last item is visible, i.e. the feed is scrolled to the bottom (or is empty).
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1
        }
    }
    // Set while we drive a programmatic scroll, so the auto-pause below ignores our own motion.
    var following by remember { mutableStateOf(false) }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun jumpToLatest() {
        autoScroll = true
        scope.launch {
            following = true
            try {
                if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
            } finally {
                following = false
            }
        }
    }

    fun copyLog() {
        if (entries.isEmpty()) {
            showMessage("Log masih kosong")
            return
        }
        clipboard.setText(AnnotatedString(EventLog.formatPlain()))
        showMessage("Log disalin")
    }

    fun shareLog() {
        if (entries.isEmpty()) {
            showMessage("Log masih kosong")
            return
        }
        val text = EventLog.formatPlain()
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(System.currentTimeMillis())
                    File(dir, "uimapper-log_$stamp.txt").apply { writeText(text) }
                }
                Exporters.share(context, file, "text/plain")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showMessage("Gagal membagikan log: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // Follow new entries while auto-scroll is on.
    LaunchedEffect(entries.lastOrNull()?.id, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) {
            following = true
            try {
                listState.animateScrollToItem(entries.lastIndex)
            } finally {
                following = false
            }
        }
    }
    // If the user scrolls up (and it is not our own scroll), stop fighting them.
    LaunchedEffect(atBottom) {
        if (!atBottom && !following) autoScroll = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = TerminalBg,
        topBar = {
            TopAppBar(
                title = { Text("Live log") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { if (autoScroll) autoScroll = false else jumpToLatest() }) {
                        if (autoScroll) {
                            Icon(Icons.Filled.Pause, contentDescription = "Jeda gulir otomatis")
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Lanjutkan gulir otomatis")
                        }
                    }
                    IconButton(onClick = { EventLog.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Bersihkan")
                    }
                    IconButton(onClick = ::copyLog) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Salin")
                    }
                    IconButton(onClick = ::shareLog) {
                        Icon(Icons.Filled.Share, contentDescription = "Bagikan")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TerminalBar,
                    titleContentColor = BarContent,
                    navigationIconContentColor = BarContent,
                    actionIconContentColor = BarContent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .background(TerminalBg),
        ) {
            LogStatusHeader(state)
            LogListArea(
                entries = entries,
                listState = listState,
                atBottom = atBottom,
                onJumpToLatest = ::jumpToLatest,
            )
        }
    }
}

/**
 * The scrolling log body plus the floating "jump to latest" button. Extracted into its own
 * composable so the [AnimatedVisibility] call resolves to the top-level (BoxScope) overload rather
 * than the outer [androidx.compose.foundation.layout.ColumnScope] extension.
 */
@Composable
private fun LogListArea(
    entries: List<EventLog.Entry>,
    listState: LazyListState,
    atBottom: Boolean,
    onJumpToLatest: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Text(
                text = "Belum ada aktivitas. Mulai rekam rute atau buka inspeksi untuk melihat log di sini.",
                style = MaterialTheme.typography.bodyMedium,
                color = TimeColor,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }

        AnimatedVisibility(
            visible = entries.isNotEmpty() && !atBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            JumpToLatestButton(onClick = onJumpToLatest)
        }
    }
}

@Composable
private fun LogStatusHeader(state: ServiceState) {
    val recording = state.mode == ServiceMode.RECORDING
    val dot = if (recording) DotRecording else DotIdle
    val text = when {
        recording -> buildList {
            add("Merekam")
            state.currentScreenId?.takeIf { it.isNotBlank() }?.let { add(it) }
            add("${state.screenCount} layar")
            add("${state.edgeCount} rute")
        }.joinToString(" · ")

        !state.connected -> state.status?.takeIf { it.isNotBlank() } ?: "Layanan tidak terhubung"
        else -> state.status?.takeIf { it.isNotBlank() } ?: "Siaga"
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                color = MessageColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        }
        HorizontalDivider(color = TerminalDivider)
    }
}

@Composable
private fun LogRow(entry: EventLog.Entry) {
    val annotated = remember(entry.id) {
        val tagColor = tagColor(entry.tag)
        buildAnnotatedString {
            withStyle(SpanStyle(color = TimeColor)) {
                append(LogTimeFormat.format(entry.timeMs))
                append("  ")
            }
            withStyle(SpanStyle(color = tagColor, fontWeight = FontWeight.Bold)) {
                append(entry.tag.name.padEnd(TAG_WIDTH))
            }
            append("  ")
            withStyle(SpanStyle(color = MessageColor)) {
                append(entry.message)
            }
        }
    }
    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 17.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
    )
}

@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(50),
        color = TerminalBar,
        contentColor = BarContent,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, TerminalDivider),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("terbaru", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
