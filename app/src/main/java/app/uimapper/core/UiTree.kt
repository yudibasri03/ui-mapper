package app.uimapper.core

import app.uimapper.model.Bounds
import app.uimapper.model.ElementRef
import app.uimapper.model.UiNode

/** Pure helpers over a captured [UiNode] tree. Indices are pre-order, so children are sorted by idx. */
object UiTree {

    fun flatten(root: UiNode): List<UiNode> {
        val out = ArrayList<UiNode>()
        val stack = ArrayDeque<UiNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            out += n
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return out
    }

    /** Chain root..node (inclusive) for the node with [idx], or empty if absent. */
    fun ancestry(root: UiNode, idx: Int): List<UiNode> {
        if (idx < root.idx) return emptyList()
        val chain = ArrayList<UiNode>()
        var cur: UiNode? = root
        while (cur != null) {
            chain += cur
            if (cur.idx == idx) return chain
            // Pre-order: the subtree containing idx is the last child whose idx <= target.
            cur = cur.children.lastOrNull { it.idx <= idx }
        }
        return emptyList()
    }

    fun find(root: UiNode, idx: Int): UiNode? = ancestry(root, idx).lastOrNull()?.takeIf { it.idx == idx }

    fun parentOf(root: UiNode, idx: Int): UiNode? {
        val chain = ancestry(root, idx)
        return if (chain.size >= 2) chain[chain.size - 2] else null
    }

    /** XPath-like locator: /FrameLayout[1]/LinearLayout[2]/Button[1] (1-based among same-class siblings). */
    fun xpath(root: UiNode, idx: Int): String {
        val chain = ancestry(root, idx)
        if (chain.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in chain.indices) {
            val node = chain[i]
            val pos = if (i == 0) 1 else {
                val siblings = chain[i - 1].children
                siblings.takeWhile { it.idx != node.idx }.count { it.cls == node.cls } + 1
            }
            sb.append('/').append(node.simpleCls).append('[').append(pos).append(']')
        }
        return sb.toString()
    }

    /** All visible nodes containing (x, y), smallest area first (deepest wins ties). */
    fun hitTestAll(root: UiNode, x: Int, y: Int): List<UiNode> =
        flatten(root)
            .filter { it.visible && !it.bounds.isEmpty() && it.bounds.contains(x, y) }
            .sortedWith(compareBy<UiNode> { it.bounds.area }.thenByDescending { it.depth })

    fun hitTest(root: UiNode, x: Int, y: Int): UiNode? = hitTestAll(root, x, y).firstOrNull()

    /** Actionable (clickable / long-clickable, enabled, visible) nodes in reading order. */
    fun actionable(root: UiNode): List<UiNode> =
        flatten(root)
            .filter { it.isActionable }
            .sortedWith(compareBy<UiNode> { it.bounds.t }.thenBy { it.bounds.l })

    /**
     * Human-readable label: own text / content-description, else the first text or description in the
     * subtree (clickable containers usually wrap a TextView), else hint, else resource-id entry.
     */
    fun labelOf(node: UiNode, maxLen: Int = 60): String? {
        fun clean(s: String?): String? = s?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.take(maxLen)
        clean(node.text)?.let { return it }
        clean(node.desc)?.let { return it }
        val queue = ArrayDeque<UiNode>()
        queue.addAll(node.children)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            clean(n.text)?.let { return it }
            clean(n.desc)?.let { return it }
            queue.addAll(n.children)
        }
        return clean(node.hint) ?: clean(node.paneTitle) ?: node.resIdEntry
    }

    fun toElementRef(root: UiNode, node: UiNode): ElementRef = ElementRef(
        cls = node.cls,
        resId = node.resId,
        text = node.text,
        desc = node.desc,
        label = labelOf(node),
        bounds = node.bounds,
        path = xpath(root, node.idx),
        nodeIdx = node.idx,
    )

    /**
     * Find the node in [root] that best matches an element observed elsewhere (e.g. an event source
     * node, or an element from an older snapshot of the same screen). Returns null if nothing is close.
     */
    fun match(
        root: UiNode,
        cls: String?,
        resId: String?,
        text: String?,
        desc: String?,
        bounds: Bounds?,
    ): UiNode? {
        var best: UiNode? = null
        var bestScore = 0
        for (n in flatten(root)) {
            var score = 0
            if (resId != null && n.resId == resId) score += 4
            if (!text.isNullOrBlank() && n.text == text) score += 3
            if (!desc.isNullOrBlank() && n.desc == desc) score += 2
            if (cls != null && n.cls == cls) score += 1
            if (bounds != null && !bounds.isEmpty()) {
                if (n.bounds == bounds) score += 5
                else if (iou(n.bounds, bounds) > 0.7f) score += 2
            }
            if (n.isActionable) score += 1
            if (score > bestScore) {
                best = n
                bestScore = score
            }
        }
        return if (bestScore >= 5) best else null
    }

    fun match(root: UiNode, ref: ElementRef): UiNode? =
        match(root, ref.cls, ref.resId, ref.text, ref.desc, ref.bounds)

    fun iou(a: Bounds, b: Bounds): Float {
        if (!a.intersects(b)) return 0f
        val inter = Bounds(maxOf(a.l, b.l), maxOf(a.t, b.t), minOf(a.r, b.r), minOf(a.b, b.b)).area
        val union = a.area + b.area - inter
        return if (union <= 0) 0f else inter.toFloat() / union.toFloat()
    }

    fun countNodes(root: UiNode): Int = flatten(root).size
    fun countActionable(root: UiNode): Int = flatten(root).count { it.isActionable }
}
