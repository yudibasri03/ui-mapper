package app.uimapper.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import app.uimapper.core.NodeCapture
import app.uimapper.core.UiTree
import app.uimapper.model.UiNode
import kotlin.math.hypot

/**
 * Pre-computed drawing/hit-testing data for one capture. Build it off the main thread with [build].
 */
internal class LayerData private constructor(
    val root: UiNode,
    /** Visible nodes with non-empty bounds (synthetic multi-window root excluded), pre-order. */
    val candidates: List<UiNode>,
    val others: List<UiNode>,
    val texts: List<UiNode>,
    val actions: List<UiNode>,
    /** Every captured node except the synthetic multi-window root. */
    val totalCount: Int,
    val actionableCount: Int,
) {
    companion object {
        fun build(root: UiNode): LayerData {
            val flat = UiTree.flatten(root)
            val candidates = ArrayList<UiNode>(flat.size)
            val others = ArrayList<UiNode>()
            val texts = ArrayList<UiNode>()
            val actions = ArrayList<UiNode>()
            var total = 0
            for (n in flat) {
                if (n.cls == NodeCapture.SYNTHETIC_ROOT_CLS) continue
                total++
                if (!n.visible || n.bounds.isEmpty()) continue
                candidates += n
                when {
                    n.isActionable -> actions += n
                    !n.text.isNullOrBlank() || !n.desc.isNullOrBlank() -> texts += n
                    else -> others += n
                }
            }
            return LayerData(root, candidates, others, texts, actions, total, actions.size)
        }
    }
}

/**
 * Full-screen, touch-consuming layer shown while inspecting. Draws the bounds of every captured node
 * over the inspected app and lets the user pick one:
 *  - tap: selects the smallest element under the finger; tapping again near the same spot cycles to the
 *    next larger enclosing element;
 *  - drag: live "probe" of the smallest element under the finger.
 *
 * Node bounds are screen coordinates; the view's on-screen offset is subtracted when drawing and added
 * when hit-testing so both line up exactly regardless of how the window is inset.
 * This view never forwards anything to the app underneath.
 */
internal class InspectLayerView(context: Context) : View(context) {

    interface Listener {
        fun onSelectionChanged(node: UiNode?, fromTouch: Boolean)

        /** The layer was resized after its first layout (rotation, display change). */
        fun onLayerResized()
    }

    var listener: Listener? = null

    private var data: LayerData? = null

    var selected: UiNode? = null
        private set
    private var ancestors: List<UiNode> = emptyList()

    /** "Hanya yang bisa diklik": draw (and prefer when picking) actionable nodes only. */
    var onlyActionable: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var message: String? = null

    // ---- geometry ----
    private val loc = IntArray(2)
    private var offX = 0
    private var offY = 0
    private val rect = RectF()

    // ---- touch ----
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val cycleRadius = context.dpf(24f)
    private var downX = 0f
    private var downY = 0f
    private var probing = false
    private var hasLastTap = false
    private var lastTapX = 0
    private var lastTapY = 0

    // ---- paints ----
    private val strokeW = context.dpf(1f)
    private val scrimColor = 0x29000000
    private val otherPaint = strokePaint(0x4D94A3B8, strokeW)
    private val textPaint = strokePaint(0xB33B82F6.toInt(), strokeW)
    private val actionPaint = strokePaint(0xE622C55E.toInt(), strokeW)
    private val ancestorPaint = strokePaint(0xD9FDBA74.toInt(), context.dpf(1.5f)).apply {
        isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(context.dpf(6f), context.dpf(4f)), 0f)
    }
    private val selFillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x40F97316
    }
    private val selStrokeW = context.dpf(3f)
    private val selStrokePaint = strokePaint(OverlayColors.ORANGE, selStrokeW).apply { isAntiAlias = true }
    private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayColors.ORANGE
    }
    private val tagTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayColors.NEAR_BLACK
        typeface = Typeface.DEFAULT_BOLD
        textSize = context.spf(12f)
    }
    private val msgBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayColors.SURFACE
    }
    private val msgTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayColors.TEXT
        textSize = context.spf(14f)
    }
    private val tagPadH = context.dpf(5f)
    private val tagPadV = context.dpf(2.5f)
    private val tagRadius = context.dpf(4f)
    private val tagGap = context.dpf(2f)

    init {
        contentDescription = "Lapisan inspeksi UI Mapper"
        isClickable = true
    }

    private fun strokePaint(color: Int, width: Float) = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
        isAntiAlias = false
    }

    // ---------------------------------------------------------------- public API

    /** Replace the captured tree (null clears it). Selection is cleared without notifying. */
    fun setData(newData: LayerData?) {
        data = newData
        selected = null
        ancestors = emptyList()
        hasLastTap = false
        invalidate()
    }

    /** Centered status pill (e.g. "Memuat struktur..."), or null to hide it. */
    fun setMessage(text: String?) {
        if (message == text) return
        message = text
        invalidate()
    }

    fun select(node: UiNode?, notify: Boolean = true, fromTouch: Boolean = false) {
        val d = data
        val target = if (d == null) null else node
        if (target?.idx == selected?.idx && target != null) {
            return
        }
        selected = target
        ancestors = if (target != null && d != null) {
            UiTree.ancestry(d.root, target.idx)
                .dropLast(1)
                .filter { it.cls != NodeCapture.SYNTHETIC_ROOT_CLS && it.visible && !it.bounds.isEmpty() }
        } else {
            emptyList()
        }
        invalidate()
        if (notify) listener?.onSelectionChanged(target, fromTouch)
    }

    // ---------------------------------------------------------------- geometry

    private fun updateOffset() {
        getLocationOnScreen(loc)
        if (loc[0] != offX || loc[1] != offY) {
            offX = loc[0]
            offY = loc[1]
            invalidate()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateOffset()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) listener?.onLayerResized()
    }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateOffset()
                downX = event.x
                downY = event.y
                probing = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!probing && hypot(event.x - downX, event.y - downY) > touchSlop) probing = true
                if (probing) probeAt(event.x, event.y)
            }
            MotionEvent.ACTION_UP -> {
                if (probing) {
                    probing = false
                    // A later tap near the release point cycles outwards from the probed element.
                    lastTapX = (event.x + offX).toInt()
                    lastTapY = (event.y + offY).toInt()
                    hasLastTap = selected != null
                } else {
                    tapAt(event.x, event.y)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> probing = false
        }
        // Always consume: nothing reaches the inspected app while the layer is shown.
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun candidatesAt(sx: Int, sy: Int): List<UiNode> {
        val d = data ?: return emptyList()
        val all = UiTree.hitTestAll(d.root, sx, sy).filter { it.cls != NodeCapture.SYNTHETIC_ROOT_CLS }
        if (!onlyActionable) return all
        val act = all.filter { it.isActionable }
        return act.ifEmpty { all }
    }

    private fun tapAt(x: Float, y: Float) {
        if (data == null) return
        val sx = (x + offX).toInt()
        val sy = (y + offY).toInt()
        val cands = candidatesAt(sx, sy)
        if (cands.isEmpty()) {
            hasLastTap = false
            select(null, notify = true, fromTouch = true)
            return
        }
        val near = hasLastTap && hypot((sx - lastTapX).toFloat(), (sy - lastTapY).toFloat()) <= cycleRadius
        val next = if (near) {
            val cur = selected?.let { s -> cands.indexOfFirst { it.idx == s.idx } } ?: -1
            cands[(cur + 1) % cands.size]
        } else {
            cands[0]
        }
        lastTapX = sx
        lastTapY = sy
        hasLastTap = true
        select(next, notify = true, fromTouch = true)
    }

    private fun probeAt(x: Float, y: Float) {
        val d = data ?: return
        val sx = (x + offX).toInt()
        val sy = (y + offY).toInt()
        val hit = smallestAt(d, sx, sy, onlyActionable) ?: if (onlyActionable) smallestAt(d, sx, sy, false) else null
        if (hit != null && hit.idx != selected?.idx) select(hit, notify = true, fromTouch = true)
    }

    private fun smallestAt(d: LayerData, sx: Int, sy: Int, actionableOnly: Boolean): UiNode? {
        var best: UiNode? = null
        for (n in d.candidates) {
            if (actionableOnly && !n.isActionable) continue
            if (!n.bounds.contains(sx, sy)) continue
            val b = best
            if (b == null || n.bounds.area < b.bounds.area || (n.bounds.area == b.bounds.area && n.depth > b.depth)) {
                best = n
            }
        }
        return best
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(scrimColor)
        val d = data
        if (d != null) {
            if (!onlyActionable) {
                drawRects(canvas, d.others, otherPaint)
                drawRects(canvas, d.texts, textPaint)
            }
            drawRects(canvas, d.actions, actionPaint)
            for (a in ancestors) {
                setRect(a, ancestorPaint.strokeWidth / 2f)
                canvas.drawRect(rect, ancestorPaint)
            }
            selected?.let { drawSelection(canvas, it) }
        }
        message?.let { drawMessage(canvas, it) }
    }

    private fun setRect(n: UiNode, inset: Float) {
        val b = n.bounds
        rect.set(
            (b.l - offX).toFloat() + inset,
            (b.t - offY).toFloat() + inset,
            (b.r - offX).toFloat() - inset,
            (b.b - offY).toFloat() - inset,
        )
    }

    private fun drawRects(canvas: Canvas, nodes: List<UiNode>, paint: Paint) {
        val inset = paint.strokeWidth / 2f
        for (i in nodes.indices) {
            setRect(nodes[i], inset)
            canvas.drawRect(rect, paint)
        }
    }

    private fun drawSelection(canvas: Canvas, n: UiNode) {
        if (n.bounds.isEmpty()) return
        setRect(n, 0f)
        canvas.drawRect(rect, selFillPaint)
        setRect(n, selStrokeW / 2f)
        canvas.drawRect(rect, selStrokePaint)

        val label = n.simpleCls + (n.resIdEntry?.let { " · $it" } ?: "")
        val maxW = (width - 2 * tagGap - 2 * tagPadH).coerceAtLeast(0f)
        val text = TextUtils.ellipsize(label, tagTextPaint, maxW, TextUtils.TruncateAt.END).toString()
        if (text.isEmpty()) return
        val fm = tagTextPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val tagW = tagTextPaint.measureText(text) + 2 * tagPadH
        val tagH = textH + 2 * tagPadV

        setRect(n, 0f)
        var top = rect.top - tagH - tagGap
        if (top < tagGap) {
            top = rect.bottom + tagGap
            if (top + tagH > height - tagGap) top = rect.top + tagGap
        }
        var left = rect.left
        if (left + tagW > width - tagGap) left = width - tagGap - tagW
        if (left < tagGap) left = tagGap
        rect.set(left, top, left + tagW, top + tagH)
        canvas.drawRoundRect(rect, tagRadius, tagRadius, tagBgPaint)
        canvas.drawText(text, left + tagPadH, top + tagPadV - fm.ascent, tagTextPaint)
    }

    private fun drawMessage(canvas: Canvas, msg: String) {
        val maxW = (width - context.dpf(64f)).coerceAtLeast(0f)
        val text = TextUtils.ellipsize(msg, msgTextPaint, maxW, TextUtils.TruncateAt.END).toString()
        if (text.isEmpty()) return
        val fm = msgTextPaint.fontMetrics
        val padH = context.dpf(16f)
        val padV = context.dpf(10f)
        val w = msgTextPaint.measureText(text) + 2 * padH
        val h = (fm.descent - fm.ascent) + 2 * padV
        val left = (width - w) / 2f
        val top = height * 0.4f - h / 2f
        rect.set(left, top, left + w, top + h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, msgBgPaint)
        canvas.drawText(text, left + padH, top + padV - fm.ascent, msgTextPaint)
    }
}
