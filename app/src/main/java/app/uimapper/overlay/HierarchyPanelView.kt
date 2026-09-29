package app.uimapper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import app.uimapper.core.NodeCapture
import app.uimapper.core.UiTree
import app.uimapper.model.UiNode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A scrollable, DevTools-style node tree drawn on a single canvas (one row per captured node). Only the
 * rows inside the visible viewport are drawn, so a large tree stays responsive; the flattened row list is
 * built once per capture (see [buildRows]) off the main thread and handed in with [setRows].
 *
 * Selection is not owned here: the controller keeps the single source of truth (the selected [UiNode]) and
 * pushes it in with [setSelectedNodeIdx]; a tap on a row reports back through [onRowSelected]. The view only
 * mirrors and scrolls to that selection. It never touches the app underneath.
 */
internal class HierarchyPanelView(context: Context) : View(context) {

    /** One pre-computed tree row. Strings are composed once; drawing only measures/ellipsizes them. */
    class Row(
        val nodeIdx: Int,
        val node: UiNode,
        val depth: Int,
        val cls: String,
        /** "#entry" for the resource-id, or null. */
        val resIdPart: String?,
        /** Raw text/desc/hint (single line, trimmed), drawn quoted, or null. */
        val textPart: String?,
        val clickable: Boolean,
        val scrollable: Boolean,
        val editable: Boolean,
    )

    /** Invoked with the tapped row's node (never the synthetic multi-window root). */
    var onRowSelected: ((UiNode) -> Unit)? = null

    private var rows: List<Row> = emptyList()
    private val idxToRow = HashMap<Int, Int>()
    private var selectedIdx: Int = -1

    /** Height ceiling in px (the tree scrolls beyond it); 0 means unbounded. */
    var maxHeightPx: Int = 0
        set(value) {
            val v = value.coerceAtLeast(0)
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    // ---- metrics ----
    private val rowH = context.dpi(30)
    private val indentStep = context.dpi(14)
    private val padH = context.dpi(10)
    private val padV = context.dpi(6)
    private val partGap = context.dpf(6f)
    private val markerGap = context.dpf(5f)
    private val accentW = context.dpf(3f)

    // ---- paints ----
    private fun rowPaint(color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = Typeface.MONOSPACE
        textSize = context.spf(12f)
    }

    private val clsPaint = rowPaint(OverlayColors.TEXT)
    private val resIdPaint = rowPaint(OverlayColors.BLUE_LIGHT)
    private val textValPaint = rowPaint(OverlayColors.TEXT_2)
    private val clickMarkerPaint = rowPaint(OverlayColors.GREEN_LIGHT)
    private val scrollMarkerPaint = rowPaint(OverlayColors.BLUE_LIGHT)
    private val editMarkerPaint = rowPaint(OverlayColors.ORANGE)
    private val emptyPaint = rowPaint(OverlayColors.MUTED).apply { textAlign = Paint.Align.CENTER }

    private val selFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x33F97316
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayColors.ORANGE
    }
    private val guidePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1f)
        color = 0x22FFFFFF
        isAntiAlias = false
    }
    private val rect = RectF()

    // ---- scrolling / touch ----
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    init {
        contentDescription = "Pohon hierarki UI Mapper"
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_NEVER
        isClickable = true
    }

    // ---------------------------------------------------------------- public API

    fun setRows(newRows: List<Row>) {
        rows = newRows
        idxToRow.clear()
        newRows.forEachIndexed { i, r -> idxToRow[r.nodeIdx] = i }
        scroller.forceFinished(true)
        // Keep the offset valid for the new content height.
        super.scrollTo(0, scrollY.coerceIn(0, maxScroll()))
        requestLayout()
        invalidate()
    }

    fun setSelectedNodeIdx(idx: Int) {
        if (selectedIdx == idx) return
        selectedIdx = idx
        invalidate()
    }

    /** Bring the row for [nodeIdx] into view (centred), animating when already laid out. */
    fun scrollToNode(nodeIdx: Int) {
        val row = idxToRow[nodeIdx] ?: return
        val run = Runnable {
            val target = (padV + row * rowH - (height - rowH) / 2).coerceIn(0, maxScroll())
            scroller.forceFinished(true)
            if (isLaidOut) {
                scroller.startScroll(0, scrollY, 0, target - scrollY, SCROLL_ANIM_MS)
                postInvalidateOnAnimation()
            } else {
                super.scrollTo(0, target)
            }
        }
        if (height == 0 || !isLaidOut) post(run) else run.run()
    }

    // ---------------------------------------------------------------- measurement / scrolling

    private fun contentHeight(): Int = rows.size * rowH + 2 * padV

    private fun maxScroll(): Int = max(0, contentHeight() - height)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desired = contentHeight()
        var h = if (maxHeightPx > 0) min(desired, maxHeightPx) else desired
        when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> h = MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> h = min(h, MeasureSpec.getSize(heightMeasureSpec))
            else -> Unit
        }
        setMeasuredDimension(w, h)
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(0, y.coerceIn(0, maxScroll()))
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            super.scrollTo(0, scroller.currY.coerceIn(0, maxScroll()))
            postInvalidateOnAnimation()
        }
    }

    override fun computeVerticalScrollRange(): Int = contentHeight()

    override fun computeVerticalScrollExtent(): Int = height

    override fun computeVerticalScrollOffset(): Int = scrollY

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = event.x
                downY = event.y
                lastY = event.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.y - downY) > touchSlop) dragging = true
                if (dragging) {
                    val dy = (lastY - event.y).toInt()
                    scrollBy(0, dy)
                    lastY = event.y
                    awakenScrollBars()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) {
                    tapAt(event.y)
                    performClick()
                } else {
                    tracker.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vy = tracker.yVelocity
                    if (abs(vy) > minFling) {
                        scroller.fling(0, scrollY, 0, -vy.toInt(), 0, 0, 0, maxScroll())
                        postInvalidateOnAnimation()
                    }
                }
                recycleTracker()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                recycleTracker()
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun tapAt(y: Float) {
        if (rows.isEmpty()) return
        val row = ((scrollY + y - padV) / rowH).toInt()
        if (row < 0 || row >= rows.size) return
        val node = rows[row].node
        if (node.cls == NodeCapture.SYNTHETIC_ROOT_CLS) return
        setSelectedNodeIdx(node.idx)
        onRowSelected?.invoke(node)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rows.isEmpty()) {
            val cx = width / 2f
            val fm = emptyPaint.fontMetrics
            canvas.drawText(MSG_EMPTY, cx, scrollY + height / 2f - (fm.ascent + fm.descent) / 2f, emptyPaint)
            return
        }
        val top = scrollY
        val bottom = scrollY + height
        val first = max(0, (top - padV) / rowH)
        val last = min(rows.size - 1, (bottom - padV) / rowH)
        val fm = clsPaint.fontMetrics
        val markerW = clsPaint.measureText("•")
        for (i in first..last) {
            val row = rows[i]
            val rowTop = (padV + i * rowH).toFloat()
            val baseline = rowTop + rowH / 2f - (fm.ascent + fm.descent) / 2f
            if (row.nodeIdx == selectedIdx) {
                rect.set(0f, rowTop, width.toFloat(), rowTop + rowH)
                canvas.drawRect(rect, selFillPaint)
                rect.set(0f, rowTop + context.dpf(4f), accentW, rowTop + rowH - context.dpf(4f))
                canvas.drawRect(rect, accentPaint)
            }
            drawRow(canvas, row, rowTop, baseline, markerW)
        }
    }

    private fun drawRow(canvas: Canvas, row: Row, rowTop: Float, baseline: Float, markerW: Float) {
        // Indent guides for each ancestor level.
        var guideX = padH + indentStep / 2f
        for (d in 0 until row.depth) {
            canvas.drawLine(guideX, rowTop, guideX, rowTop + rowH, guidePaint)
            guideX += indentStep
        }

        // Reserve the trailing marker column on the right.
        var markers = 0
        if (row.clickable) markers++
        if (row.scrollable) markers++
        if (row.editable) markers++
        val markersWidth = if (markers > 0) markers * markerW + (markers - 1) * markerGap else 0f
        val rightLimit = width - padH - if (markers > 0) markersWidth + partGap else 0f

        var x = (padH + row.depth * indentStep).toFloat()
        // Class name.
        val clsText = TextUtils.ellipsize(row.cls, clsPaint, (rightLimit - x).coerceAtLeast(0f), TextUtils.TruncateAt.END)
        canvas.drawText(clsText, 0, clsText.length, x, baseline, clsPaint)
        x += clsPaint.measureText(clsText.toString()) + partGap

        // Resource-id.
        if (row.resIdPart != null && x < rightLimit) {
            val r = TextUtils.ellipsize(row.resIdPart, resIdPaint, (rightLimit - x).coerceAtLeast(0f), TextUtils.TruncateAt.END)
            canvas.drawText(r, 0, r.length, x, baseline, resIdPaint)
            x += resIdPaint.measureText(r.toString()) + partGap
        }

        // Text / description value, quoted.
        if (row.textPart != null && x < rightLimit) {
            val quoted = "\"" + row.textPart + "\""
            val t = TextUtils.ellipsize(quoted, textValPaint, (rightLimit - x).coerceAtLeast(0f), TextUtils.TruncateAt.END)
            canvas.drawText(t, 0, t.length, x, baseline, textValPaint)
        }

        // Markers, left to right in the reserved column.
        if (markers > 0) {
            var mx = width - padH - markersWidth
            if (row.clickable) {
                canvas.drawText("•", mx, baseline, clickMarkerPaint)
                mx += markerW + markerGap
            }
            if (row.scrollable) {
                canvas.drawText("↕", mx, baseline, scrollMarkerPaint)
                mx += markerW + markerGap
            }
            if (row.editable) {
                canvas.drawText("✎", mx, baseline, editMarkerPaint)
            }
        }
    }

    companion object {
        private const val SCROLL_ANIM_MS = 220
        private const val MSG_EMPTY = "Tidak ada elemen"

        /**
         * Flatten [root] into tree rows, once per capture. The synthetic multi-window root is skipped and
         * its subtrees are lifted to depth 0 so indentation starts at the real roots.
         */
        fun buildRows(root: UiNode): List<Row> {
            val depthOffset = if (root.cls == NodeCapture.SYNTHETIC_ROOT_CLS) 1 else 0
            val flat = UiTree.flatten(root)
            val out = ArrayList<Row>(flat.size)
            for (n in flat) {
                if (n.cls == NodeCapture.SYNTHETIC_ROOT_CLS) continue
                out += Row(
                    nodeIdx = n.idx,
                    node = n,
                    depth = (n.depth - depthOffset).coerceAtLeast(0),
                    cls = n.simpleCls.ifEmpty { "View" },
                    resIdPart = n.resIdEntry?.let { "#$it" },
                    textPart = shortText(n),
                    clickable = n.clickable || n.longClickable,
                    scrollable = n.scrollable,
                    editable = n.editable,
                )
            }
            return out
        }

        private fun shortText(n: UiNode): String? {
            // Editable/password nodes carry no text by design; fall back to hint so the row still reads.
            val raw = n.text ?: n.desc ?: n.hint
            return raw?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
        }
    }
}
