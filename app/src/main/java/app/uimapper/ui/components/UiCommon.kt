@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package app.uimapper.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.uimapper.core.RouteFinder
import app.uimapper.data.SessionStore
import app.uimapper.model.ActionType
import app.uimapper.model.EdgeSource
import app.uimapper.model.NavEdge
import app.uimapper.model.Session
import app.uimapper.model.SessionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// =============================================================================================
// Labels & formatting (Bahasa Indonesia)
// =============================================================================================

private val ID_LOCALE: Locale = Locale.forLanguageTag("id-ID")
private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", ID_LOCALE)
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", ID_LOCALE)

private const val MAX_NAME_LENGTH = 80

fun modeLabel(mode: SessionMode): String = when (mode) {
    SessionMode.RECORD -> "Rekam"
    SessionMode.EXPLORE -> "Jelajah"
    SessionMode.SNAPSHOT -> "Snapshot"
}

fun sourceLabel(source: EdgeSource): String = when (source) {
    EdgeSource.MANUAL -> "Manual"
    EdgeSource.AUTO -> "Otomatis"
}

/** True when [id] is a screen that still exists in [session] (not START, not an external app). */
fun isScreenNode(session: Session, id: String): Boolean = session.screen(id) != null

/** User-facing text for a failed [SessionStore] write, e.g. "Nama tidak tersimpan: penyimpanan penuh ...". */
fun storeErrorMessage(action: String, e: Throwable): String {
    val reason = if (e is IOException) {
        "penyimpanan penuh atau tidak dapat ditulis"
    } else {
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }
    return "$action: $reason"
}

/**
 * Runs a blocking [SessionStore] write on Dispatchers.IO. A failure (e.g. a full disk) is reported through
 * [onError] instead of crashing the app, and with it the accessibility service running in the same
 * process. Returns true on success.
 */
suspend fun storeWrite(action: String, onError: (String) -> Unit, block: () -> Unit): Boolean = try {
    withContext(Dispatchers.IO) { block() }
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError(storeErrorMessage(action, e))
    false
}

fun formatDateTime(ts: Long): String =
    if (ts <= 0L) "—" else DATE_TIME_FORMAT.format(Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()))

/** "baru saja", "5 menit lalu", "3 jam lalu", "kemarin, 14:05", "4 hari lalu", else a full date. */
fun formatRelativeTime(ts: Long, now: Long = System.currentTimeMillis()): String {
    if (ts <= 0L) return "—"
    val diff = now - ts
    if (diff < 0L) return formatDateTime(ts)
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(ts).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
    return when {
        diff < 60_000L -> "baru saja"
        diff < 3_600_000L -> "${diff / 60_000L} menit lalu"
        days == 0L -> "${diff / 3_600_000L} jam lalu"
        days == 1L -> "kemarin, ${TIME_FORMAT.format(then)}"
        days < 7L -> "$days hari lalu"
        else -> formatDateTime(ts)
    }
}

/** Wall-clock time that ticks every [periodMs], so relative times stay fresh on screen. */
@Composable
fun rememberNow(periodMs: Long = 30_000L): Long {
    // Lint false positive (compose-runtime 1.7): the keyed produceState overloads are always
    // flagged even though the producer assigns `value` below.
    @android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
    val now by produceState(System.currentTimeMillis(), periodMs) {
        while (true) {
            delay(periodMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

// =============================================================================================
// Screenshot loading (off the main thread, downsampled, memory-cached)
// =============================================================================================

private fun screenshotCacheSizeKb(): Int =
    (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceIn(8 * 1024, 96 * 1024)

private object ScreenshotCache {
    /** Keyed by "<absolute path>@<lastModified>@<target width>". */
    private val cache = object : LruCache<String, Bitmap>(screenshotCacheSizeKb()) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    /** Last file key resolved for a "<session>/<screen>/<shot>/<width>" key; lets the UI peek without I/O. */
    private val latestByScreen = HashMap<String, String>()

    @Synchronized
    fun peek(screenKey: String): Bitmap? = latestByScreen[screenKey]?.let { cache.get(it) }

    @Synchronized
    fun get(fileKey: String): Bitmap? = cache.get(fileKey)

    @Synchronized
    fun put(screenKey: String, fileKey: String, bitmap: Bitmap) {
        latestByScreen[screenKey] = fileKey
        cache.put(fileKey, bitmap)
    }
}

/**
 * Screenshot of [screenId], decoded on Dispatchers.IO with BitmapFactory inSampleSize so that it is at
 * least [targetWidth] px wide (or its natural size). Returns null while loading or when there is none.
 * Decoded bitmaps are cached per file path + lastModified + width.
 */
@Composable
fun rememberScreenshot(sessionId: String, screenId: String, shotName: String?, targetWidth: Int): ImageBitmap? {
    val screenKey = "$sessionId/$screenId/${shotName.orEmpty()}/$targetWidth"
    val state = remember(screenKey) {
        mutableStateOf(if (shotName == null) null else ScreenshotCache.peek(screenKey)?.asImageBitmap())
    }
    LaunchedEffect(screenKey) {
        if (shotName == null || state.value != null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) { loadScreenshot(sessionId, screenId, screenKey, targetWidth) }
        if (bitmap != null) state.value = bitmap.asImageBitmap()
    }
    return state.value
}

private fun loadScreenshot(sessionId: String, screenId: String, screenKey: String, targetWidth: Int): Bitmap? {
    val file = SessionStore.screenshotFile(sessionId, screenId) ?: return null
    val fileKey = "${file.absolutePath}@${file.lastModified()}@$targetWidth"
    ScreenshotCache.get(fileKey)?.let { hit ->
        ScreenshotCache.put(screenKey, fileKey, hit)
        return hit
    }
    val bitmap = decodeSampled(file, targetWidth) ?: return null
    ScreenshotCache.put(screenKey, fileKey, bitmap)
    return bitmap
}

private fun decodeSampled(file: File, targetWidth: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    val wanted = targetWidth.coerceAtLeast(1)
    while (bounds.outWidth / (sample * 2) >= wanted) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    BitmapFactory.decodeFile(file.absolutePath, opts)
}.getOrNull()

// =============================================================================================
// Small building blocks
// =============================================================================================

@Composable
fun TagChip(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    icon: ImageVector? = null,
) {
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ModeChip(mode: SessionMode, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (container, content) = when (mode) {
        SessionMode.RECORD -> cs.primaryContainer to cs.onPrimaryContainer
        SessionMode.EXPLORE -> cs.secondaryContainer to cs.onSecondaryContainer
        SessionMode.SNAPSHOT -> cs.tertiaryContainer to cs.onTertiaryContainer
    }
    TagChip(modeLabel(mode), modifier = modifier, containerColor = container, contentColor = content)
}

/** Colour used for an edge of this action type in lists and in the navigation map. */
@Composable
fun actionColor(action: ActionType): Color {
    val cs = MaterialTheme.colorScheme
    return when (action) {
        ActionType.CLICK, ActionType.LONG_CLICK, ActionType.SCROLL, ActionType.TEXT_INPUT -> cs.primary
        ActionType.LAUNCH -> cs.secondary
        ActionType.EXTERNAL -> cs.tertiary
        ActionType.BACK, ActionType.UNKNOWN -> cs.outline
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(cs.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = cs.onSecondaryContainer)
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, message: String? = null) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NotFoundContent(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.SearchOff,
        title = "Tidak ditemukan",
        message = message,
        modifier = modifier.fillMaxSize(),
        action = { Button(onClick = onBack) { Text("Kembali") } },
    )
}

/** Muted explanatory line used inside lists. */
@Composable
fun InfoText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(cs.surfaceContainer)
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = cs.primary,
            modifier = Modifier.weight(1f),
        )
        if (count != null) TagChip("$count")
        if (expanded != null) {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Tutup bagian" else "Buka bagian",
                modifier = Modifier.padding(start = 8.dp).rotate(if (expanded) 180f else 0f),
            )
        }
    }
}

// =============================================================================================
// Dialogs
// =============================================================================================

@Composable
fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    fieldLabel: String = "Nama",
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val trimmed = value.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.replace('\n', ' ').take(MAX_NAME_LENGTH) },
                label = { Text(fieldLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (trimmed.isNotEmpty()) onConfirm(trimmed) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(trimmed) }, enabled = trimmed.isNotEmpty()) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

// =============================================================================================
// Route / edge rows shared by the session and screen detail screens
// =============================================================================================

/** One numbered step of a route: "Di S1 · Beranda" / "Ketuk "Masuk"" / "→ S2 · Login". */
@Composable
fun RouteStepRow(
    number: Int,
    session: Session,
    edge: NavEdge,
    onOpenScreen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val targetIsScreen = isScreenNode(session, edge.to)
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(24.dp).clip(CircleShape).background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = cs.onPrimaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Di ${RouteFinder.nodeLabel(session, edge.from)}",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                RouteFinder.stepText(edge),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "→ ${RouteFinder.nodeLabel(session, edge.to)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (targetIsScreen) cs.primary else cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = if (targetIsScreen) {
                    Modifier.clickable { onOpenScreen(edge.to) }.padding(vertical = 2.dp)
                } else {
                    Modifier.padding(vertical = 2.dp)
                },
            )
        }
    }
}

/** Numbered list of route steps. */
@Composable
fun RouteSteps(session: Session, steps: List<NavEdge>, onOpenScreen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        steps.forEachIndexed { i, edge -> RouteStepRow(i + 1, session, edge, onOpenScreen) }
    }
}

/**
 * A navigation edge as "S1 · Beranda —[Ketuk "Masuk"]→ S2 · Login" with its action, source, count and
 * time. [onClick] / [onLongClick] make the row interactive; [onDelete] adds a trailing delete button.
 */
@Composable
fun EdgeRow(
    session: Session,
    edge: NavEdge,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val color = actionColor(edge.action)
    val interaction = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick ?: {}, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(interaction)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                RouteFinder.describe(session, edge),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TagChip(
                    RouteFinder.actionVerb(edge.action),
                    containerColor = color.copy(alpha = 0.16f),
                    contentColor = cs.onSurface,
                )
                TagChip(sourceLabel(edge.source))
                TagChip(
                    "×${edge.count}",
                    containerColor = cs.surfaceVariant,
                    contentColor = cs.onSurfaceVariant,
                )
                Text(
                    formatRelativeTime(edge.lastAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Hapus transisi", tint = cs.onSurfaceVariant)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}
