@file:OptIn(ExperimentalMaterial3Api::class)

package app.uimapper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.uimapper.core.RouteFinder
import app.uimapper.model.ActionType
import app.uimapper.model.EXTERNAL_PREFIX
import app.uimapper.model.NavEdge
import app.uimapper.model.START_NODE
import app.uimapper.model.Session
import app.uimapper.model.isExternalNode
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private const val GRAPH_MAX_PER_ROW = 6
private const val GRAPH_MIN_SCALE = 0.12f
private const val GRAPH_MAX_SCALE = 3f
private const val GRAPH_FIT_MAX_SCALE = 1.25f

// =============================================================================================
// Public composable
// =============================================================================================

/**
 * Navigation map of a session: screens as boxes in BFS layers from the start screen, edges as arrows
 * coloured by action type. Pan / pinch to move, tap a screen box to open it.
 */
@Composable
fun GraphView(session: Session, onOpenScreen: (String) -> Unit, modifier: Modifier = Modifier) {
    if (session.screens.isEmpty() && session.edges.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.AccountTree,
            title = "Peta masih kosong",
            message = "Rekam navigasi aplikasi target untuk melihat peta layar dan rute tombolnya di sini.",
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val density = LocalDensity.current
    val dims = remember(density.density, density.fontScale) { GraphDims(density.density, density.fontScale) }
    val layout = remember(session, dims) { buildGraphLayout(session, dims) }
    val colorScheme = MaterialTheme.colorScheme
    val palette = remember(colorScheme) { graphPalette(colorScheme) }
    val textMeasurer = rememberTextMeasurer()
    val nodeTexts = remember(layout, palette, textMeasurer, dims) {
        layout.nodes.map { measureNodeText(textMeasurer, it, palette, dims) }
    }
    var showLabels by rememberSaveable { mutableStateOf(false) }
    val edgeLabels = remember(layout, palette, textMeasurer, dims, showLabels) {
        if (showLabels) layout.edges.map { measureEdgeLabel(textMeasurer, it.edge, palette, dims) } else emptyList()
    }

    var camScale by remember { mutableFloatStateOf(1f) }
    var camOffset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var userMoved by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var legendOpen by rememberSaveable { mutableStateOf(true) }

    val topReserve = 60f * density.density
    val margin = 16f * density.density

    fun fitToView() {
        if (viewSize.width <= 0 || viewSize.height <= 0) return
        val b = layout.bounds
        if (b.width <= 0f || b.height <= 0f) return
        val availW = (viewSize.width - 2f * margin).coerceAtLeast(1f)
        val availH = (viewSize.height - topReserve - 2f * margin).coerceAtLeast(1f)
        val s = min(availW / b.width, availH / b.height).coerceIn(GRAPH_MIN_SCALE, GRAPH_FIT_MAX_SCALE)
        camScale = s
        camOffset = Offset(
            viewSize.width / 2f - b.center.x * s,
            topReserve + margin + availH / 2f - b.center.y * s,
        )
    }

    fun zoomBy(factor: Float) {
        val c = Offset(viewSize.width / 2f, viewSize.height / 2f)
        val newScale = (camScale * factor).coerceIn(GRAPH_MIN_SCALE, GRAPH_MAX_SCALE)
        val k = newScale / camScale
        camOffset = c - (c - camOffset) * k
        camScale = newScale
        userMoved = true
    }

    LaunchedEffect(layout, viewSize) {
        if (!userMoved) fitToView()
    }
    LaunchedEffect(info) {
        if (info != null) {
            delay(2_500L)
            info = null
        }
    }

    val latestLayout by rememberUpdatedState(layout)
    val latestOnOpen by rememberUpdatedState(onOpenScreen)
    val hitSlop = dims.hitSlop

    Box(modifier.fillMaxSize().clipToBounds().background(palette.background)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (camScale * zoom).coerceIn(GRAPH_MIN_SCALE, GRAPH_MAX_SCALE)
                        val k = newScale / camScale
                        camOffset = centroid - (centroid - camOffset) * k + pan
                        camScale = newScale
                        userMoved = true
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { p ->
                        val world = (p - camOffset) / camScale
                        val slop = hitSlop / camScale
                        val hit = latestLayout.nodes.lastOrNull { it.rect.inflate(slop).contains(world) }
                        if (hit != null) {
                            when (hit.kind) {
                                GraphNodeKind.SCREEN -> latestOnOpen(hit.id)
                                GraphNodeKind.START -> info = "Mulai: titik saat aplikasi dibuka"
                                GraphNodeKind.EXTERNAL -> info = "Aplikasi lain: ${hit.subtitle}"
                                GraphNodeKind.UNKNOWN -> info = "Layar ${hit.id} sudah tidak ada di sesi ini"
                            }
                        }
                    })
                },
        ) {
            drawGraph(layout, nodeTexts, edgeLabels, palette, dims, camScale, camOffset)
        }

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showLabels,
                onClick = { showLabels = !showLabels },
                label = { Text("Label") },
                leadingIcon = { Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            FilledTonalIconButton(onClick = { zoomBy(1f / 1.3f) }) {
                Icon(Icons.Filled.ZoomOut, contentDescription = "Perkecil")
            }
            FilledTonalIconButton(onClick = { zoomBy(1.3f) }) {
                Icon(Icons.Filled.ZoomIn, contentDescription = "Perbesar")
            }
            FilledTonalButton(onClick = {
                userMoved = false
                fitToView()
            }) {
                Icon(Icons.Filled.FitScreen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paskan")
            }
        }

        GraphLegend(
            palette = palette,
            summary = "${session.screens.size} layar · ${session.edges.size} transisi",
            open = legendOpen,
            onToggle = { legendOpen = !legendOpen },
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        )

        info?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
                    .widthIn(max = 360.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 4.dp,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// =============================================================================================
// Legend
// =============================================================================================

@Composable
private fun GraphLegend(
    palette: GraphPalette,
    summary: String,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (open) {
                LegendLine(palette.primary, dashed = false, text = "Ketuk / tekan lama")
                LegendLine(palette.secondary, dashed = false, text = "Buka aplikasi")
                LegendLine(palette.tertiary, dashed = false, text = "Ke aplikasi lain")
                LegendLine(palette.outline, dashed = true, text = "Kembali / tak diketahui")
                Text(
                    "Ketuk kotak layar untuk membuka detail",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Ketuk untuk legenda",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 24.dp, height = 10.dp)) {
            val y = size.height / 2f
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f) else null,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall)
    }
}

// =============================================================================================
// Model
// =============================================================================================

private enum class GraphNodeKind { START, SCREEN, EXTERNAL, UNKNOWN }

private class GraphNode(
    val id: String,
    val kind: GraphNodeKind,
    val title: String,
    val subtitle: String,
    val isStartScreen: Boolean,
    /** World coordinates (px at zoom 1). */
    val rect: Rect,
)

private class GraphEdge(
    val edge: NavEdge,
    val path: Path,
    val arrow: Path,
    val labelAt: Offset,
    val bbox: Rect,
    val width: Float,
    val dashed: Boolean,
)

private class GraphLayout(val nodes: List<GraphNode>, val edges: List<GraphEdge>, val bounds: Rect)

private class NodeText(val title: TextLayoutResult, val subtitle: TextLayoutResult?)

/** Pixel sizes derived from density; [px] is the dp -> px factor. */
private class GraphDims(px: Float, fontScale: Float) {
    val nodeW = 150f * px
    val nodeH = (18f + 42f * fontScale.coerceAtLeast(1f)) * px
    val startW = 96f * px
    val startH = (20f + 20f * fontScale.coerceAtLeast(1f)) * px
    val gapX = 40f * px
    val gapY = 84f * px
    val pad = 8f * px
    val corner = 10f * px
    val bendStep = 22f * px
    val loopSize = 30f * px
    val arrowLen = 10f * px
    val arrowW = 8f * px
    val stroke = 1.6f * px
    val strokeStep = 0.35f * px
    val dashOn = 6f * px
    val dashOff = 4f * px
    val labelPad = 4f * px
    val labelMaxW = (170f * px).toInt()
    val hitSlop = 6f * px
}

private class GraphPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val outline: Color,
    val nodeFill: Color,
    val nodeStroke: Color,
    val nodeText: Color,
    val nodeSubText: Color,
    val startScreenFill: Color,
    val startScreenText: Color,
    val startFill: Color,
    val startText: Color,
    val extFill: Color,
    val extStroke: Color,
    val extText: Color,
    val unknownFill: Color,
    val unknownStroke: Color,
    val unknownText: Color,
    val labelBg: Color,
    val labelText: Color,
    val background: Color,
) {
    fun edgeColor(action: ActionType): Color = when (action) {
        ActionType.CLICK, ActionType.LONG_CLICK, ActionType.SCROLL, ActionType.TEXT_INPUT -> primary
        ActionType.LAUNCH -> secondary
        ActionType.EXTERNAL -> tertiary
        ActionType.BACK, ActionType.UNKNOWN -> outline
    }
}

private fun graphPalette(cs: ColorScheme) = GraphPalette(
    primary = cs.primary,
    secondary = cs.secondary,
    tertiary = cs.tertiary,
    outline = cs.outline,
    nodeFill = cs.surfaceContainerHigh,
    nodeStroke = cs.outlineVariant,
    nodeText = cs.onSurface,
    nodeSubText = cs.onSurfaceVariant,
    startScreenFill = cs.primaryContainer,
    startScreenText = cs.onPrimaryContainer,
    startFill = cs.primary,
    startText = cs.onPrimary,
    extFill = cs.tertiaryContainer,
    extStroke = cs.tertiary,
    extText = cs.onTertiaryContainer,
    unknownFill = cs.errorContainer,
    unknownStroke = cs.error,
    unknownText = cs.onErrorContainer,
    labelBg = cs.surface.copy(alpha = 0.92f),
    labelText = cs.onSurface,
    background = cs.surfaceContainerLowest,
)

// =============================================================================================
// Layout
// =============================================================================================

private fun buildGraphLayout(session: Session, d: GraphDims): GraphLayout {
    val edges = session.edges
    val screenById = session.screens.associateBy { it.id }
    val screenIds = session.screens.map { it.id }

    val referenced = LinkedHashSet<String>()
    for (e in edges) {
        referenced += e.from
        referenced += e.to
    }
    val hasStart = START_NODE in referenced
    val extIds = referenced.filter { isExternalNode(it) }.sorted()
    val unknownIds = referenced.filter { it != START_NODE && !isExternalNode(it) && it !in screenById }

    // ---- layers ----
    val base = if (hasStart) 1 else 0
    val layer = HashMap<String, Int>()
    if (hasStart) layer[START_NODE] = 0
    val depths = RouteFinder.depths(session)
    for (id in screenIds) depths[id]?.let { layer[id] = it + base }
    for (id in unknownIds) depths[id]?.let { layer[id] = it + base }

    fun placeExternals() {
        for (x in extIds) {
            if (x in layer) continue
            val src = edges.asSequence()
                .filter { it.to == x && it.from != x }
                .mapNotNull { layer[it.from] }
                .minOrNull()
            if (src != null) layer[x] = src + 1
        }
    }
    placeExternals()
    val finalLayer = (layer.values.maxOrNull() ?: (base - 1)) + 1
    for (id in screenIds) if (id !in layer) layer[id] = finalLayer
    for (id in unknownIds) if (id !in layer) layer[id] = finalLayer
    placeExternals()
    for (x in extIds) if (x !in layer) layer[x] = finalLayer + 1

    // ---- order inside layers (one barycenter pass, top-down) ----
    val rank = HashMap<String, Int>()
    var next = 0
    if (hasStart) rank[START_NODE] = next++
    for (id in screenIds) rank[id] = next++
    for (id in unknownIds) rank[id] = next++
    for (id in extIds) rank[id] = next++

    val neighbours = HashMap<String, MutableSet<String>>()
    for (e in edges) {
        if (e.from == e.to) continue
        neighbours.getOrPut(e.from) { HashSet() } += e.to
        neighbours.getOrPut(e.to) { HashSet() } += e.from
    }

    val byLayer = layer.entries.groupBy({ it.value }, { it.key }).toSortedMap()
    val xPos = HashMap<String, Float>()
    val rows = ArrayList<List<String>>()
    for ((_, ids) in byLayer) {
        val initial = ids.sortedBy { rank[it] ?: Int.MAX_VALUE }
        val n = initial.size
        val keyed = initial.mapIndexed { i, id ->
            val placed = neighbours[id].orEmpty().mapNotNull { xPos[it] }
            id to (if (placed.isEmpty()) (i + 0.5f) / n else placed.average().toFloat())
        }
        val sorted = keyed.sortedBy { it.second }.map { it.first }
        for (chunk in sorted.chunked(GRAPH_MAX_PER_ROW)) {
            chunk.forEachIndexed { i, id -> xPos[id] = (i + 0.5f) / chunk.size }
            rows += chunk
        }
    }

    // ---- positions ----
    val rectOf = HashMap<String, Rect>()
    val rowOf = HashMap<String, Int>()
    val slot = d.nodeW + d.gapX
    val rowStep = max(d.nodeH, d.startH) + d.gapY
    rows.forEachIndexed { r, ids ->
        val cy = r * rowStep + d.nodeH / 2f
        val n = ids.size
        ids.forEachIndexed { i, id ->
            val cx = (i - (n - 1) / 2f) * slot
            val w = if (id == START_NODE) d.startW else d.nodeW
            val h = if (id == START_NODE) d.startH else d.nodeH
            rectOf[id] = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            rowOf[id] = r
        }
    }

    val startScreen = RouteFinder.startScreenId(session)
    val nodes = rows.flatten().map { id ->
        val summary = screenById[id]
        val kind = when {
            id == START_NODE -> GraphNodeKind.START
            isExternalNode(id) -> GraphNodeKind.EXTERNAL
            summary != null -> GraphNodeKind.SCREEN
            else -> GraphNodeKind.UNKNOWN
        }
        val isStartScreen = kind == GraphNodeKind.SCREEN && id == startScreen
        val title = when (kind) {
            GraphNodeKind.START -> "Mulai"
            GraphNodeKind.EXTERNAL -> "Aplikasi lain"
            GraphNodeKind.SCREEN -> {
                val visits = summary?.visits ?: 0
                buildString {
                    append(id)
                    if (isStartScreen) append(" · awal")
                    if (visits > 1) append(" · ${visits}×")
                }
            }
            GraphNodeKind.UNKNOWN -> id
        }
        val subtitle = when (kind) {
            GraphNodeKind.START -> ""
            GraphNodeKind.EXTERNAL -> id.removePrefix(EXTERNAL_PREFIX)
            GraphNodeKind.SCREEN -> summary?.label.orEmpty()
            GraphNodeKind.UNKNOWN -> "(layar sudah dihapus)"
        }
        GraphNode(id, kind, title, subtitle, isStartScreen, rectOf.getValue(id))
    }

    // ---- edges ----
    val graphEdges = ArrayList<GraphEdge>()
    val groups = edges.groupBy { if (it.from <= it.to) "${it.from}\u0000${it.to}" else "${it.to}\u0000${it.from}" }
    for ((_, group) in groups) {
        val k = group.size
        group.forEachIndexed { i, e ->
            val ra = rectOf[e.from] ?: return@forEachIndexed
            val rb = rectOf[e.to] ?: return@forEachIndexed
            graphEdges += if (e.from == e.to) {
                selfLoopEdge(e, ra, i, d)
            } else {
                val span = abs((rowOf[e.from] ?: 0) - (rowOf[e.to] ?: 0))
                curvedEdge(e, ra, rb, i, k, span, d)
            }
        }
    }

    var bounds: Rect? = null
    fun grow(r: Rect) {
        val b = bounds
        bounds = if (b == null) r else Rect(min(b.left, r.left), min(b.top, r.top), max(b.right, r.right), max(b.bottom, r.bottom))
    }
    nodes.forEach { grow(it.rect) }
    graphEdges.forEach { grow(it.bbox) }

    return GraphLayout(nodes, graphEdges, bounds ?: Rect.Zero)
}

private fun edgeStrokeWidth(e: NavEdge, d: GraphDims): Float =
    d.stroke + d.strokeStep * (e.count - 1).coerceIn(0, 6)

private fun isDashed(e: NavEdge): Boolean = e.action == ActionType.BACK || e.action == ActionType.UNKNOWN

private fun curvedEdge(e: NavEdge, ra: Rect, rb: Rect, index: Int, count: Int, rowSpan: Int, d: GraphDims): GraphEdge {
    // A canonical direction shared by both directions of a pair keeps parallel edges fanned apart.
    val forward = e.from <= e.to
    val ca = if (forward) ra.center else rb.center
    val cb = if (forward) rb.center else ra.center
    val dx = cb.x - ca.x
    val dy = cb.y - ca.y
    val len = hypot(dx, dy).coerceAtLeast(1f)
    val px = -dy / len
    val py = dx / len

    val baseBend = when {
        rowSpan == 0 && len > (d.nodeW + d.gapX) * 1.5f -> len * 0.22f // hop over boxes in between
        rowSpan > 1 -> len * 0.12f
        else -> 0f
    }
    val bend = baseBend + (index - (count - 1) / 2f) * d.bendStep
    val from = ra.center
    val to = rb.center
    val mid = Offset((ca.x + cb.x) / 2f, (ca.y + cb.y) / 2f)
    val ctrl = Offset(mid.x + px * bend, mid.y + py * bend)
    val straight = abs(bend) < 0.5f

    val start = boundaryPoint(ra, if (straight) to else ctrl)
    val end = boundaryPoint(rb, if (straight) from else ctrl)
    val dirFrom = if (straight) start else ctrl
    val ux0 = end.x - dirFrom.x
    val uy0 = end.y - dirFrom.y
    val ul = hypot(ux0, uy0).coerceAtLeast(0.001f)
    val ux = ux0 / ul
    val uy = uy0 / ul
    val strokeEnd = Offset(end.x - ux * d.arrowLen * 0.6f, end.y - uy * d.arrowLen * 0.6f)

    val path = Path().apply {
        moveTo(start.x, start.y)
        if (straight) {
            lineTo(strokeEnd.x, strokeEnd.y)
        } else {
            // Quadratic Bézier (start, ctrl, strokeEnd) expressed as a cubic.
            val c1 = start + (ctrl - start) * (2f / 3f)
            val c2 = strokeEnd + (ctrl - strokeEnd) * (2f / 3f)
            cubicTo(c1.x, c1.y, c2.x, c2.y, strokeEnd.x, strokeEnd.y)
        }
    }
    val labelAt = if (straight) {
        Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    } else {
        Offset(
            0.25f * start.x + 0.5f * ctrl.x + 0.25f * end.x,
            0.25f * start.y + 0.5f * ctrl.y + 0.25f * end.y,
        )
    }
    val bbox = Rect(
        min(start.x, min(ctrl.x, end.x)),
        min(start.y, min(ctrl.y, end.y)),
        max(start.x, max(ctrl.x, end.x)),
        max(start.y, max(ctrl.y, end.y)),
    ).inflate(d.arrowLen)
    return GraphEdge(e, path, arrowHead(end, ux, uy, d), labelAt, bbox, edgeStrokeWidth(e, d), isDashed(e))
}

private fun selfLoopEdge(e: NavEdge, r: Rect, index: Int, d: GraphDims): GraphEdge {
    val size = d.loopSize * (1f + 0.45f * index)
    val y1 = r.center.y - r.height * 0.22f
    val y2 = r.center.y + r.height * 0.22f
    val start = Offset(r.right, y1)
    val end = Offset(r.right, y2)
    val c1 = Offset(r.right + size, y1 - size * 0.7f)
    val c2 = Offset(r.right + size, y2 + size * 0.7f)
    val ux0 = end.x - c2.x
    val uy0 = end.y - c2.y
    val ul = hypot(ux0, uy0).coerceAtLeast(0.001f)
    val ux = ux0 / ul
    val uy = uy0 / ul
    val strokeEnd = Offset(end.x - ux * d.arrowLen * 0.6f, end.y - uy * d.arrowLen * 0.6f)
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, strokeEnd.x, strokeEnd.y)
    }
    val bbox = Rect(r.right, y1 - size * 0.7f, r.right + size, y2 + size * 0.7f).inflate(d.arrowLen)
    return GraphEdge(
        edge = e,
        path = path,
        arrow = arrowHead(end, ux, uy, d),
        labelAt = Offset(r.right + size * 0.8f, r.center.y),
        bbox = bbox,
        width = edgeStrokeWidth(e, d),
        dashed = isDashed(e),
    )
}

/** Point where the ray from the centre of [r] towards [toward] leaves the rectangle. */
private fun boundaryPoint(r: Rect, toward: Offset): Offset {
    val c = r.center
    val dx = toward.x - c.x
    val dy = toward.y - c.y
    if (abs(dx) < 1e-3f && abs(dy) < 1e-3f) return c
    val hw = r.width / 2f
    val hh = r.height / 2f
    val tx = if (abs(dx) < 1e-3f) Float.MAX_VALUE else hw / abs(dx)
    val ty = if (abs(dy) < 1e-3f) Float.MAX_VALUE else hh / abs(dy)
    val t = min(tx, ty)
    return Offset(c.x + dx * t, c.y + dy * t)
}

private fun arrowHead(tip: Offset, ux: Float, uy: Float, d: GraphDims): Path {
    val bx = tip.x - ux * d.arrowLen
    val by = tip.y - uy * d.arrowLen
    val nx = -uy * d.arrowW / 2f
    val ny = ux * d.arrowW / 2f
    return Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(bx + nx, by + ny)
        lineTo(bx - nx, by - ny)
        close()
    }
}

// =============================================================================================
// Text
// =============================================================================================

private fun measureNodeText(tm: TextMeasurer, n: GraphNode, p: GraphPalette, d: GraphDims): NodeText {
    val maxW = (n.rect.width - d.pad * 2f).toInt().coerceAtLeast(1)
    val (titleColor, subColor) = when (n.kind) {
        GraphNodeKind.START -> p.startText to p.startText
        GraphNodeKind.EXTERNAL -> p.extText to p.extText
        GraphNodeKind.UNKNOWN -> p.unknownText to p.unknownText
        GraphNodeKind.SCREEN ->
            if (n.isStartScreen) p.startScreenText to p.startScreenText else p.nodeText to p.nodeSubText
    }
    val title = tm.measure(
        text = n.title,
        style = TextStyle(color = titleColor, fontSize = 11.sp, fontWeight = FontWeight.Bold),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(maxWidth = maxW),
    )
    val subtitle = if (n.subtitle.isBlank()) {
        null
    } else {
        tm.measure(
            text = n.subtitle,
            style = TextStyle(color = subColor, fontSize = 12.sp, lineHeight = 14.sp),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = Constraints(maxWidth = maxW),
        )
    }
    return NodeText(title, subtitle)
}

private fun edgeLabelText(e: NavEdge): String {
    val step = RouteFinder.stepText(e)
    val base = if (step.length > 34) step.take(33) + "…" else step
    return if (e.count > 1) "$base ×${e.count}" else base
}

private fun measureEdgeLabel(tm: TextMeasurer, e: NavEdge, p: GraphPalette, d: GraphDims): TextLayoutResult =
    tm.measure(
        text = edgeLabelText(e),
        style = TextStyle(color = p.labelText, fontSize = 10.sp),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
        constraints = Constraints(maxWidth = d.labelMaxW.coerceAtLeast(1)),
    )

// =============================================================================================
// Drawing
// =============================================================================================

private fun DrawScope.drawGraph(
    layout: GraphLayout,
    nodeTexts: List<NodeText>,
    edgeLabels: List<TextLayoutResult>,
    palette: GraphPalette,
    dims: GraphDims,
    camScale: Float,
    camOffset: Offset,
) {
    // Keep strokes readable when zoomed out.
    val inv = 1f / min(camScale, 1f)
    val visible = Rect(
        -camOffset.x / camScale,
        -camOffset.y / camScale,
        (size.width - camOffset.x) / camScale,
        (size.height - camOffset.y) / camScale,
    )
    val dash = PathEffect.dashPathEffect(floatArrayOf(dims.dashOn * inv, dims.dashOff * inv), 0f)

    withTransform({
        translate(camOffset.x, camOffset.y)
        scale(camScale, camScale, Offset.Zero)
    }) {
        for (ge in layout.edges) {
            if (!ge.bbox.overlaps(visible)) continue
            val color = palette.edgeColor(ge.edge.action)
            drawPath(
                path = ge.path,
                color = color,
                style = Stroke(width = ge.width * inv, cap = StrokeCap.Round, pathEffect = if (ge.dashed) dash else null),
            )
            drawPath(path = ge.arrow, color = color)
        }

        if (edgeLabels.size == layout.edges.size) {
            for (i in layout.edges.indices) {
                val ge = layout.edges[i]
                if (!visible.contains(ge.labelAt)) continue
                val tl = edgeLabels[i]
                val w = tl.size.width.toFloat()
                val h = tl.size.height.toFloat()
                val topLeft = Offset(ge.labelAt.x - w / 2f, ge.labelAt.y - h / 2f)
                drawRoundRect(
                    color = palette.labelBg,
                    topLeft = Offset(topLeft.x - dims.labelPad, topLeft.y - dims.labelPad / 2f),
                    size = Size(w + dims.labelPad * 2f, h + dims.labelPad),
                    cornerRadius = CornerRadius(dims.labelPad),
                )
                drawText(tl, topLeft = topLeft)
            }
        }

        if (nodeTexts.size == layout.nodes.size) {
            for (i in layout.nodes.indices) {
                val n = layout.nodes[i]
                if (!n.rect.overlaps(visible)) continue
                drawGraphNode(n, nodeTexts[i], palette, dims)
            }
        }
    }
}

private fun DrawScope.drawGraphNode(n: GraphNode, t: NodeText, p: GraphPalette, d: GraphDims) {
    val r = n.rect
    val (fill, stroke, strokeWidth) = when (n.kind) {
        GraphNodeKind.START -> Triple(p.startFill, p.startFill, d.stroke)
        GraphNodeKind.EXTERNAL -> Triple(p.extFill, p.extStroke, d.stroke)
        GraphNodeKind.UNKNOWN -> Triple(p.unknownFill, p.unknownStroke, d.stroke)
        GraphNodeKind.SCREEN ->
            if (n.isStartScreen) Triple(p.startScreenFill, p.primary, d.stroke * 2f)
            else Triple(p.nodeFill, p.nodeStroke, d.stroke)
    }
    val radius = if (n.kind == GraphNodeKind.START) CornerRadius(r.height / 2f) else CornerRadius(d.corner)
    drawRoundRect(color = fill, topLeft = r.topLeft, size = r.size, cornerRadius = radius)
    drawRoundRect(color = stroke, topLeft = r.topLeft, size = r.size, cornerRadius = radius, style = Stroke(width = strokeWidth))

    if (n.kind == GraphNodeKind.START) {
        drawText(
            t.title,
            topLeft = Offset(r.center.x - t.title.size.width / 2f, r.center.y - t.title.size.height / 2f),
        )
    } else {
        val x = r.left + d.pad
        val titleTop = r.top + d.pad * 0.75f
        drawText(t.title, topLeft = Offset(x, titleTop))
        t.subtitle?.let { sub ->
            drawText(sub, topLeft = Offset(x, titleTop + t.title.size.height + d.pad * 0.25f))
        }
    }
}
