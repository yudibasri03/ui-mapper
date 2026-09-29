package app.uimapper.model

import kotlinx.serialization.Serializable

/** Screen-space rectangle in real device pixels (same space as AccessibilityNodeInfo.getBoundsInScreen). */
@Serializable
data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
    val area: Long get() = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
    val centerX: Int get() = (l + r) / 2
    val centerY: Int get() = (t + b) / 2
    fun isEmpty(): Boolean = width <= 0 || height <= 0
    fun contains(x: Int, y: Int): Boolean = x in l until r && y in t until b
    fun contains(o: Bounds): Boolean = o.l >= l && o.t >= t && o.r <= r && o.b <= b
    fun intersects(o: Bounds): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
    fun union(o: Bounds): Bounds = Bounds(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
    override fun toString(): String = "[$l,$t][$r,$b]"

    companion object {
        val EMPTY = Bounds(0, 0, 0, 0)
    }
}

/**
 * One captured accessibility node. [idx] is the pre-order index inside its snapshot (root = 0) and is
 * the stable way to refer to a node within a single [ScreenSnapshot].
 *
 * Privacy: [text] is always null for password fields and for editable fields (only [hint] is kept).
 */
@Serializable
data class UiNode(
    val idx: Int,
    val depth: Int,
    val cls: String,
    val pkg: String? = null,
    val resId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val hint: String? = null,
    val paneTitle: String? = null,
    val bounds: Bounds = Bounds.EMPTY,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val selected: Boolean = false,
    val visible: Boolean = true,
    val heading: Boolean = false,
    /** Standard action names (e.g. "click", "long_click", "scroll_forward") plus custom action labels. */
    val actions: List<String> = emptyList(),
    val children: List<UiNode> = emptyList(),
) {
    /** "android.widget.Button" -> "Button". */
    val simpleCls: String get() = cls.substringAfterLast('.')

    /** "com.foo:id/btn_login" -> "btn_login". */
    val resIdEntry: String? get() = resId?.substringAfter(":id/", resId)

    val isActionable: Boolean get() = (clickable || longClickable) && enabled && visible && !bounds.isEmpty()
}

enum class ActionType { CLICK, LONG_CLICK, SCROLL, TEXT_INPUT, BACK, LAUNCH, EXTERNAL, UNKNOWN }

enum class EdgeSource { MANUAL, AUTO }

enum class SessionMode { RECORD, EXPLORE, SNAPSHOT }

/** Reference to the UI element that triggered a navigation edge. */
@Serializable
data class ElementRef(
    val cls: String? = null,
    val resId: String? = null,
    val text: String? = null,
    val desc: String? = null,
    /** Best human-readable label (own text/desc, or first text found in its subtree). */
    val label: String? = null,
    val bounds: Bounds? = null,
    /** XPath-like locator inside the source screen, e.g. /FrameLayout[1]/LinearLayout[1]/Button[2]. */
    val path: String? = null,
    /** [UiNode.idx] inside the source screen snapshot, when it could be matched. */
    val nodeIdx: Int? = null,
) {
    /** Identity used to de-duplicate edges and to remember which elements were already tried. */
    val key: String get() = resId ?: text ?: desc ?: label ?: path ?: bounds?.toString() ?: "?"

    fun display(): String =
        label ?: text ?: desc ?: resId?.substringAfter(":id/") ?: cls?.substringAfterLast('.') ?: "?"
}

/** Full capture of one screen, stored as sessions/<sessionId>/screens/<id>.json. */
@Serializable
data class ScreenSnapshot(
    val id: String,
    val pkg: String,
    val activity: String? = null,
    val title: String? = null,
    val label: String = "",
    val signature: String = "",
    val features: List<String> = emptyList(),
    val capturedAt: Long = 0L,
    val screenW: Int = 0,
    val screenH: Int = 0,
    /** File name inside sessions/<sessionId>/shots/, or null when no screenshot was taken. */
    val screenshot: String? = null,
    val nodeCount: Int = 0,
    val clickableCount: Int = 0,
    val root: UiNode,
)

/** Light-weight per-screen entry kept inside session.json. */
@Serializable
data class ScreenSummary(
    val id: String,
    val label: String,
    val pkg: String,
    val activity: String? = null,
    val title: String? = null,
    val signature: String = "",
    val features: List<String> = emptyList(),
    val screenshot: String? = null,
    val screenW: Int = 0,
    val screenH: Int = 0,
    val nodeCount: Int = 0,
    val clickableCount: Int = 0,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val visits: Int = 1,
)

/**
 * Directed navigation edge. [from]/[to] are screen ids ("S1", "S2", ...) or the special ids
 * [START_NODE] and "ext:<package>" (see [externalNodeId]).
 */
@Serializable
data class NavEdge(
    val id: String,
    val from: String,
    val to: String,
    val action: ActionType,
    val element: ElementRef? = null,
    val source: EdgeSource = EdgeSource.MANUAL,
    val firstAt: Long = 0L,
    val lastAt: Long = 0L,
    val count: Int = 1,
) {
    val dedupeKey: String get() = "$from|$to|$action|${element?.key ?: ""}"
}

@Serializable
data class Session(
    val id: String,
    val name: String,
    val targetPkg: String? = null,
    val appLabel: String? = null,
    val mode: SessionMode = SessionMode.RECORD,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val device: String? = null,
    val screens: List<ScreenSummary> = emptyList(),
    val edges: List<NavEdge> = emptyList(),
    /** Next numeric suffix for screen ids (S1, S2, ...). */
    val nextScreenNo: Int = 1,
    val nextEdgeNo: Int = 1,
) {
    fun screen(id: String): ScreenSummary? = screens.firstOrNull { it.id == id }
    fun outgoing(id: String): List<NavEdge> = edges.filter { it.from == id }
    fun incoming(id: String): List<NavEdge> = edges.filter { it.to == id }
}

const val START_NODE = "START"
const val EXTERNAL_PREFIX = "ext:"

fun externalNodeId(pkg: String): String = EXTERNAL_PREFIX + pkg
fun isExternalNode(id: String): Boolean = id.startsWith(EXTERNAL_PREFIX)
