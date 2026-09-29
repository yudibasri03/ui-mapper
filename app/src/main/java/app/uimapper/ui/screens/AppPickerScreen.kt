package app.uimapper.ui.screens

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.uimapper.core.AppInfo
import app.uimapper.core.LaunchableApp
import app.uimapper.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PickerIconSize = 40.dp

/** Byte-bounded icon cache shared by the rows of one picker screen. */
private class PickerIconCache(maxBytes: Int) : LruCache<String, ImageBitmap>(maxBytes) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private fun pickerCacheBytes(): Int =
    (Runtime.getRuntime().maxMemory() / 16L).coerceIn(4L * 1024 * 1024, 24L * 1024 * 1024).toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(onPicked: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    val apps by produceState<List<LaunchableApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching { AppInfo.launchableApps(appContext) }.getOrDefault(emptyList())
        }
    }
    val selectedPkg = remember { AppSettings.targetPkg }
    val iconCache = remember { PickerIconCache(pickerCacheBytes()) }

    val trimmed = query.trim()
    val filtered = remember(apps, trimmed) {
        val all = apps.orEmpty()
        if (trimmed.isEmpty()) {
            all
        } else {
            all.filter { it.label.contains(trimmed, ignoreCase = true) || it.pkg.contains(trimmed, ignoreCase = true) }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Pilih aplikasi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        // safeDrawing includes the IME, so the list stays scrollable above the keyboard.
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { inner ->
        val direction = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = inner.calculateStartPadding(direction),
                    top = inner.calculateTopPadding(),
                    end = inner.calculateEndPadding(direction),
                ),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("Cari nama aplikasi atau paket") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Hapus pencarian")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )

            val loaded = apps
            when {
                loaded == null -> PickerCenteredMessage(bottom = inner.calculateBottomPadding()) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Memuat daftar aplikasi...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                loaded.isEmpty() -> PickerCenteredMessage(bottom = inner.calculateBottomPadding()) {
                    Icon(
                        Icons.Outlined.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Tidak ada aplikasi yang dapat dibuka ditemukan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                filtered.isEmpty() -> PickerCenteredMessage(bottom = inner.calculateBottomPadding()) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Tidak ada aplikasi yang cocok dengan \"$trimmed\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                else -> {
                    Text(
                        text = if (trimmed.isEmpty()) {
                            "${loaded.size} aplikasi"
                        } else {
                            "${filtered.size} dari ${loaded.size} aplikasi"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = inner.calculateBottomPadding() + 8.dp),
                    ) {
                        items(items = filtered, key = { it.pkg }) { app ->
                            PickerAppRow(
                                app = app,
                                selected = app.pkg == selectedPkg,
                                cache = iconCache,
                                onClick = {
                                    keyboard?.hide()
                                    AppSettings.setTarget(app.pkg, app.label)
                                    onPicked()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerCenteredMessage(bottom: Dp, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 32.dp, end = 32.dp, bottom = bottom),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

@Composable
private fun PickerAppRow(
    app: LaunchableApp,
    selected: Boolean,
    cache: PickerIconCache,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Pilih ${app.label}", onClick = onClick)
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerAppIcon(pkg = app.pkg, cache = cache, size = PickerIconSize)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.pkg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = "Dipilih",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Icon loaded lazily per row on Dispatchers.IO and kept in [cache] by package name. */
@Composable
private fun PickerAppIcon(pkg: String, cache: PickerIconCache, size: Dp) {
    val context = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtLeast(1)
    // Lint false positive (compose-runtime 1.7): the keyed produceState overloads are always
    // flagged even though the producer assigns `value` below.
    @android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
    val icon by produceState<ImageBitmap?>(initialValue = cache.get(pkg), pkg, px) {
        val cached = cache.get(pkg)
        if (cached != null) {
            value = cached
            return@produceState
        }
        value = null
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                AppInfo.icon(context, pkg)?.toBitmap(px, px, Bitmap.Config.ARGB_8888)?.asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            cache.put(pkg, loaded)
            value = loaded
        }
    }

    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Android,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}
