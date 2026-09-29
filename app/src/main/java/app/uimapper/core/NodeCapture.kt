package app.uimapper.core

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import app.uimapper.model.Bounds
import app.uimapper.model.ScreenSnapshot
import app.uimapper.model.UiNode
import kotlinx.coroutines.CancellationException

/** Converts live [AccessibilityNodeInfo] trees into immutable, serializable [UiNode] trees. */
object NodeCapture {

    const val SYNTHETIC_ROOT_CLS = "#windows"

    class Result(
        val root: UiNode,
        val nodeCount: Int,
        val clickableCount: Int,
        /** Package of the first non-null node (the captured app). */
        val pkg: String?,
        val truncated: Boolean,
    )

    private class Ctx(val maxNodes: Int, val maxDepth: Int, val isCancelled: () -> Boolean) {
        var next = 0
        var clickable = 0
        var truncated = false
        var pkg: String? = null
    }

    private val ACTION_NAMES: Map<Int, String> = mapOf(
        AccessibilityNodeInfo.ACTION_CLICK to "click",
        AccessibilityNodeInfo.ACTION_LONG_CLICK to "long_click",
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "scroll_forward",
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "scroll_backward",
        AccessibilityNodeInfo.ACTION_FOCUS to "focus",
        AccessibilityNodeInfo.ACTION_SELECT to "select",
        AccessibilityNodeInfo.ACTION_SET_TEXT to "set_text",
        AccessibilityNodeInfo.ACTION_EXPAND to "expand",
        AccessibilityNodeInfo.ACTION_COLLAPSE to "collapse",
        AccessibilityNodeInfo.ACTION_DISMISS to "dismiss",
        AccessibilityNodeInfo.ACTION_COPY to "copy",
        AccessibilityNodeInfo.ACTION_PASTE to "paste",
        AccessibilityNodeInfo.ACTION_CUT to "cut",
        AccessibilityAction.ACTION_SHOW_ON_SCREEN.id to "show_on_screen",
        AccessibilityAction.ACTION_SCROLL_TO_POSITION.id to "scroll_to_position",
        AccessibilityAction.ACTION_SCROLL_UP.id to "scroll_up",
        AccessibilityAction.ACTION_SCROLL_DOWN.id to "scroll_down",
        AccessibilityAction.ACTION_SCROLL_LEFT.id to "scroll_left",
        AccessibilityAction.ACTION_SCROLL_RIGHT.id to "scroll_right",
        AccessibilityAction.ACTION_CONTEXT_CLICK.id to "context_click",
        AccessibilityAction.ACTION_SET_PROGRESS.id to "set_progress",
    )

    /**
     * Capture one or more window roots. With several roots (e.g. activity + dialog/popup window) a
     * synthetic root of class [SYNTHETIC_ROOT_CLS] wraps them, ordered bottom window first.
     *
     * Blocking: every uncached child is an IPC into the observed app. [isCancelled] is polled before each
     * node, so an abandoned or timed-out capture stops (with [CancellationException]) instead of walking
     * the rest of the tree.
     */
    fun capture(
        roots: List<AccessibilityNodeInfo>,
        maxNodes: Int = 5000,
        maxDepth: Int = 80,
        isCancelled: () -> Boolean = { false },
    ): Result? {
        if (roots.isEmpty()) return null
        val ctx = Ctx(maxNodes, maxDepth, isCancelled)
        val root = if (roots.size == 1) {
            build(roots[0], 0, ctx)
        } else {
            val idx = ctx.next++
            val children = roots.map { build(it, 1, ctx) }
            val bounds = children.map { it.bounds }.filter { !it.isEmpty() }
                .reduceOrNull { a, b -> a.union(b) } ?: Bounds.EMPTY
            UiNode(idx = idx, depth = 0, cls = SYNTHETIC_ROOT_CLS, pkg = ctx.pkg, bounds = bounds, children = children)
        }
        return Result(root, ctx.next, ctx.clickable, ctx.pkg, ctx.truncated)
    }

    private fun build(n: AccessibilityNodeInfo, depth: Int, ctx: Ctx): UiNode {
        if (ctx.isCancelled()) throw CancellationException("Tree capture cancelled")
        val idx = ctx.next++
        val rect = Rect()
        n.getBoundsInScreen(rect)
        val pkg = n.packageName?.toString()
        if (ctx.pkg == null && pkg != null) ctx.pkg = pkg

        val password = n.isPassword
        val editable = n.isEditable
        // Privacy: never keep what the user typed or a password; hints are fine.
        val text = if (password || editable) null else n.text?.toString()?.take(1000)
        val clickable = n.isClickable
        if (clickable && n.isEnabled && n.isVisibleToUser) ctx.clickable++

        val children = ArrayList<UiNode>()
        if (depth < ctx.maxDepth) {
            for (i in 0 until n.childCount) {
                if (ctx.next >= ctx.maxNodes) {
                    ctx.truncated = true
                    break
                }
                if (ctx.isCancelled()) throw CancellationException("Tree capture cancelled")
                val child = try {
                    n.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                children += build(child, depth + 1, ctx)
            }
        } else if (n.childCount > 0) {
            ctx.truncated = true
        }

        val actions = n.actionList.mapNotNull { a ->
            ACTION_NAMES[a.id] ?: a.label?.toString()?.takeIf { it.isNotBlank() }?.let { "custom:$it" }
        }

        return UiNode(
            idx = idx,
            depth = depth,
            cls = n.className?.toString() ?: "android.view.View",
            pkg = pkg,
            resId = n.viewIdResourceName,
            text = text,
            desc = n.contentDescription?.toString()?.take(500),
            hint = n.hintText?.toString()?.take(200),
            paneTitle = if (Build.VERSION.SDK_INT >= 28) n.paneTitle?.toString() else null,
            bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
            clickable = clickable,
            longClickable = n.isLongClickable,
            checkable = n.isCheckable,
            checked = n.isChecked,
            enabled = n.isEnabled,
            focusable = n.isFocusable,
            focused = n.isFocused,
            scrollable = n.isScrollable,
            editable = editable,
            password = password,
            selected = n.isSelected,
            visible = n.isVisibleToUser,
            heading = if (Build.VERSION.SDK_INT >= 28) n.isHeading else false,
            actions = actions,
            children = children,
        )
    }

    /**
     * Build an un-stored [ScreenSnapshot] (id = "", label = "") from a capture. The store assigns the id
     * and label when the screen is recorded into a session.
     */
    fun toSnapshot(
        result: Result,
        pkg: String,
        activity: String?,
        screenW: Int,
        screenH: Int,
        capturedAt: Long = System.currentTimeMillis(),
    ): ScreenSnapshot {
        val features = ScreenSignature.features(result.root)
        return ScreenSnapshot(
            id = "",
            pkg = pkg,
            activity = activity,
            title = ScreenSignature.deriveTitle(result.root, screenH),
            label = "",
            signature = ScreenSignature.signature(pkg, activity, features),
            features = features,
            capturedAt = capturedAt,
            screenW = screenW,
            screenH = screenH,
            screenshot = null,
            nodeCount = result.nodeCount,
            clickableCount = result.clickableCount,
            root = result.root,
        )
    }
}
