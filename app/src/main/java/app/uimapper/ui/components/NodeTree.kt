package app.uimapper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.uimapper.core.UiTree
import app.uimapper.model.UiNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val TREE_INDENT_DP = 12
private const val TREE_MAX_INDENT_LEVELS = 24
private const val TREE_SNIPPET_LENGTH = 40

private class TreeRow(val node: UiNode, val expanded: Boolean)

/** Root, the first levels and every single-child wrapper chain below them start expanded. */
private fun initialExpanded(root: UiNode): Set<Int> {
    val out = HashSet<Int>()
    fun walk(n: UiNode, budget: Int) {
        if (n.children.isEmpty()) return
        out += n.idx
        for (c in n.children) {
            val nextBudget = if (n.children.size == 1) budget else budget - 1
            if (nextBudget > 0 && out.size < 400) walk(c, nextBudget)
        }
    }
    walk(root, 3)
    return out
}

private fun visibleTreeRows(root: UiNode, expanded: Set<Int>): List<TreeRow> {
    val out = ArrayList<TreeRow>()
    val stack = ArrayDeque<UiNode>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        val n = stack.removeLast()
        val open = n.children.isNotEmpty() && n.idx in expanded
        out += TreeRow(n, open)
        if (open) for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
    }
    return out
}

private fun treeSnippet(n: UiNode): String? {
    val raw = n.text?.takeIf { it.isNotBlank() } ?: n.desc?.takeIf { it.isNotBlank() } ?: return null
    val clean = raw.replace('\n', ' ').trim()
    return if (clean.length > TREE_SNIPPET_LENGTH) clean.take(TREE_SNIPPET_LENGTH - 1) + "…" else clean
}

/**
 * Expandable view of a captured UI tree. Only the visible (expanded) rows are materialised in a
 * LazyColumn. The ancestors of [selectedIdx] are expanded automatically and the selected row is
 * scrolled into view.
 */
@Composable
fun NodeTree(
    root: UiNode,
    selectedIdx: Int?,
    onSelect: (UiNode) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(root) { mutableStateOf(initialExpanded(root)) }
    val rows by remember(root) { derivedStateOf { visibleTreeRows(root, expanded) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(root, selectedIdx) {
        val sel = selectedIdx ?: return@LaunchedEffect
        val chain = UiTree.ancestry(root, sel)
        if (chain.isEmpty()) return@LaunchedEffect
        val needed = chain.dropLast(1).map { it.idx }
        if (!expanded.containsAll(needed)) expanded = expanded + needed
        val index = snapshotFlow { rows }
            .map { list -> list.indexOfFirst { it.node.idx == sel } }
            .first { it >= 0 }
        val info = listState.layoutInfo
        val fullyVisible = info.visibleItemsInfo.any {
            it.index == index && it.offset >= info.viewportStartOffset &&
                it.offset + it.size <= info.viewportEndOffset
        }
        if (!fullyVisible) listState.animateScrollToItem((index - 3).coerceAtLeast(0))
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                scope.launch {
                    expanded = withContext(Dispatchers.Default) {
                        UiTree.flatten(root).filter { it.children.isNotEmpty() }.map { it.idx }.toHashSet()
                    }
                }
            }) {
                Icon(Icons.Filled.UnfoldMore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Buka semua")
            }
            TextButton(onClick = { expanded = setOf(root.idx) }) {
                Icon(Icons.Filled.UnfoldLess, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Tutup semua")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${rows.size} baris",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(rows, key = { it.node.idx }) { row ->
                NodeTreeRow(
                    row = row,
                    selected = row.node.idx == selectedIdx,
                    onSelect = { onSelect(row.node) },
                    onToggle = {
                        val idx = row.node.idx
                        expanded = if (idx in expanded) expanded - idx else expanded + idx
                    },
                )
            }
        }
    }
}

@Composable
private fun NodeTreeRow(row: TreeRow, selected: Boolean, onSelect: () -> Unit, onToggle: () -> Unit) {
    val n = row.node
    val cs = MaterialTheme.colorScheme
    val idColor = cs.primary
    val snippetColor = cs.onSurfaceVariant
    val text = remember(n, idColor, snippetColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(n.simpleCls) }
            n.resIdEntry?.let { id ->
                withStyle(SpanStyle(color = idColor)) {
                    append(" #")
                    append(id)
                }
            }
            val snippet = treeSnippet(n)
            if (snippet != null) {
                withStyle(SpanStyle(color = snippetColor)) { append("  \"$snippet\"") }
            } else if (!n.hint.isNullOrBlank()) {
                withStyle(SpanStyle(color = snippetColor, fontStyle = FontStyle.Italic)) {
                    append("  [${n.hint.replace('\n', ' ').take(TREE_SNIPPET_LENGTH)}]")
                }
            }
        }
    }
    val indent = (min(n.depth, TREE_MAX_INDENT_LEVELS) * TREE_INDENT_DP).dp
    val hasChildren = n.children.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) cs.primaryContainer else Color.Transparent)
            .clickable(onClick = onSelect)
            .heightIn(min = 36.dp)
            .padding(start = 4.dp + indent, end = 8.dp)
            .alpha(if (n.visible) 1f else 0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasChildren) {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (row.expanded) "Tutup cabang" else "Buka cabang",
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggle)
                    .padding(4.dp)
                    .rotate(if (row.expanded) 0f else -90f),
            )
        } else {
            Spacer(Modifier.width(28.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) cs.onPrimaryContainer else cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (n.clickable || n.longClickable) {
            Icon(
                Icons.Filled.TouchApp,
                contentDescription = "Bisa diklik",
                tint = InspectorActionableColor,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
        if (n.scrollable) {
            Icon(
                Icons.Filled.SwapVert,
                contentDescription = "Bisa digulir",
                tint = cs.secondary,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
        if (n.editable) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Kolom input",
                tint = cs.tertiary,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
        if (hasChildren && !row.expanded) {
            Text(
                "${n.children.size}",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
