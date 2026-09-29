package app.uimapper.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/*
 * Programmatic view helpers for the floating inspector overlay. Everything here is internal to the
 * overlay package; no XML layouts are used because the views live in accessibility-overlay windows
 * owned by the AccessibilityService.
 */

internal object OverlayColors {
    const val SURFACE = 0xEB111827.toInt()
    const val SURFACE_STROKE = 0x33FFFFFF
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_2 = 0xFFCBD5E1.toInt()
    const val MUTED = 0xFF94A3B8.toInt()
    const val DIM = 0xFF64748B.toInt()
    const val SLATE = 0xFF475569.toInt()
    const val BUTTON = 0xFF334155.toInt()
    const val NEAR_BLACK = 0xFF0F172A.toInt()
    const val RED = 0xFFDC2626.toInt()
    const val RED_LIGHT = 0xFFEF4444.toInt()
    const val BLUE = 0xFF2563EB.toInt()
    const val BLUE_LIGHT = 0xFF60A5FA.toInt()
    const val GREEN = 0xFF15803D.toInt()
    const val GREEN_LIGHT = 0xFF4ADE80.toInt()
    const val ORANGE = 0xFFF97316.toInt()
    const val ORANGE_LIGHT = 0xFFFDBA74.toInt()
    const val RIPPLE = 0x40FFFFFF
}

internal fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

internal fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

internal fun Context.spf(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

internal fun roundedRect(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

/** Rounded, dark, semi-opaque card surface used by the menu, the property panel and the flash label. */
internal fun surfaceBackground(ctx: Context, radiusDp: Float = 16f): GradientDrawable =
    roundedRect(OverlayColors.SURFACE, ctx.dpf(radiusDp), ctx.dpi(1), OverlayColors.SURFACE_STROKE)

internal fun buttonBackground(ctx: Context, color: Int): Drawable {
    val r = ctx.dpf(10f)
    return RippleDrawable(
        ColorStateList.valueOf(OverlayColors.RIPPLE),
        roundedRect(color, r),
        roundedRect(0xFFFFFFFF.toInt(), r),
    )
}

/**
 * Accessibility-overlay window parameters. With [fullDisplayFrame] the window is laid out against the
 * whole display (cutout included and, on API 30+, without fitting system-bar insets) so that its x/y
 * are plain screen coordinates.
 */
internal fun overlayParams(width: Int, height: Int, flags: Int, fullDisplayFrame: Boolean): WindowManager.LayoutParams {
    val lp = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    )
    if (fullDisplayFrame) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            lp.setFitInsetsTypes(0)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    return lp
}

/** Cutout handling for the full-screen inspect layer (system-bar insets are left to the platform). */
internal fun applyCutoutAlways(lp: WindowManager.LayoutParams) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}

internal fun makeText(ctx: Context, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
    TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = true
    }

/** A flat rounded button; [fullWidth] buttons are left-aligned menu rows, others are compact grid cells. */
internal fun makeButton(ctx: Context, label: String, color: Int, fullWidth: Boolean): TextView =
    TextView(ctx).apply {
        text = label
        setTextColor(OverlayColors.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        isAllCaps = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = buttonBackground(ctx, color)
        isClickable = true
        isFocusable = false
        if (fullWidth) {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(ctx.dpi(14), 0, ctx.dpi(12), 0)
            minHeight = ctx.dpi(44)
        } else {
            gravity = Gravity.CENTER
            setPadding(ctx.dpi(4), 0, ctx.dpi(4), 0)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            // Grid cells have an exact width (weight) and height, so uniform autosize is reliable (API 26+).
            setAutoSizeTextTypeUniformWithConfiguration(10, 13, 1, TypedValue.COMPLEX_UNIT_SP)
        }
    }

internal fun setButtonColor(ctx: Context, button: TextView, color: Int) {
    button.background = buttonBackground(ctx, color)
}

internal fun setButtonEnabled(button: View, enabled: Boolean) {
    if (button.isEnabled == enabled) return
    button.isEnabled = enabled
    button.alpha = if (enabled) 1f else 0.4f
}

/** ScrollView whose height never exceeds [maxHeightPx] (the content scrolls beyond that). */
internal class MaxHeightScrollView(context: Context) : ScrollView(context) {

    var maxHeightPx: Int = View.MEASURED_SIZE_MASK
        set(value) {
            val v = value.coerceIn(0, View.MEASURED_SIZE_MASK)
            if (field != v) {
                field = v
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/** Root of a window with FLAG_WATCH_OUTSIDE_TOUCH: reports touches that land outside the window. */
internal class OutsideAwareFrame(context: Context) : FrameLayout(context) {

    var onOutsideTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

/** The 52dp round control bubble. Colour and glyph reflect the service state. */
internal class BubbleView(context: Context) : View(context) {

    enum class Look { IDLE, RECORDING, INSPECTING }

    val sizePx: Int = context.dpi(52)

    /** Invoked when the view receives a configuration change (e.g. rotation). */
    var onConfigChanged: (() -> Unit)? = null

    private var look = Look.IDLE
    private var recordingBadge = false

    /** The whole bubble, as system back-gesture exclusion (the view copies the list on every set). */
    private val exclusionRect = Rect()
    private val exclusionRects = listOf(exclusionRect)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2f)
        color = 0x80FFFFFF.toInt()
    }
    private val recRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2f)
        color = OverlayColors.RED_LIGHT
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayColors.RED_LIGHT
    }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2.5f)
        color = OverlayColors.TEXT
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(3.2f)
        strokeCap = Paint.Cap.ROUND
        color = OverlayColors.TEXT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OverlayColors.TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = context.dpf(17f)
    }
    private val badgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OverlayColors.NEAR_BLACK
    }

    init {
        contentDescription = "UI Mapper"
        isClickable = true
    }

    // The drag OnTouchListener calls performClick() on a tap; overriding keeps accessibility clicks
    // (TalkBack double-tap) on the same path as touch taps.
    override fun performClick(): Boolean = super.performClick()

    /** Returns true when the look changed (and a redraw was scheduled). */
    fun setLook(newLook: Look, badge: Boolean): Boolean {
        if (newLook == look && badge == recordingBadge) return false
        look = newLook
        recordingBadge = badge
        contentDescription = when (newLook) {
            Look.IDLE -> "UI Mapper: buka menu"
            Look.RECORDING -> "UI Mapper sedang merekam: buka menu"
            Look.INSPECTING -> "UI Mapper sedang inspeksi: buka menu"
        }
        invalidate()
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The bubble snaps to the screen edge, inside the system back-gesture zone: without this a drag that
        // starts on its outer part is taken by the system and fires Back in the app underneath.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exclusionRect.set(0, 0, width, height)
            systemGestureExclusionRects = exclusionRects
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - context.dpf(1.5f)
        fillPaint.color = when (look) {
            Look.IDLE -> OverlayColors.SLATE
            Look.RECORDING -> OverlayColors.NEAR_BLACK
            Look.INSPECTING -> OverlayColors.BLUE
        }
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        when (look) {
            Look.IDLE -> {
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText("UI", cx, baseline, textPaint)
            }
            Look.RECORDING -> {
                canvas.drawCircle(cx, cy, r - context.dpf(5f), recRingPaint)
                canvas.drawCircle(cx, cy, context.dpf(9f), dotPaint)
            }
            Look.INSPECTING -> {
                val lensR = context.dpf(8.5f)
                val lx = cx - context.dpf(2.5f)
                val ly = cy - context.dpf(2.5f)
                canvas.drawCircle(lx, ly, lensR, lensPaint)
                val k = 0.7071f
                val sx = lx + lensR * k
                val sy = ly + lensR * k
                val len = context.dpf(7f)
                canvas.drawLine(sx, sy, sx + len * k, sy + len * k, handlePaint)
            }
        }
        if (recordingBadge) {
            val bx = cx + r * 0.66f
            val by = cy - r * 0.66f
            canvas.drawCircle(bx, by, context.dpf(6.5f), badgeBorderPaint)
            canvas.drawCircle(bx, by, context.dpf(4.5f), dotPaint)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        onConfigChanged?.invoke()
    }
}

/** Contents of the bubble's pop-up menu: status lines plus a vertical list of action buttons. */
internal class MenuPanel(private val ctx: Context) {

    class Item(val label: String, val color: Int, val onClick: () -> Unit)

    val root = OutsideAwareFrame(ctx)

    private val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = surfaceBackground(ctx)
        val p = ctx.dpi(12)
        setPadding(p, p, p, p)
    }
    private val title = makeText(ctx, 15f, OverlayColors.TEXT, bold = true).apply {
        text = "UI Mapper"
    }
    private val status = makeText(ctx, 13f, OverlayColors.TEXT_2).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status2 = makeText(ctx, 12f, OverlayColors.MUTED).apply {
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    private val scroll = MaxHeightScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        card.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(2)
            },
        )
        card.addView(
            status2,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(2)
            },
        )
        scroll.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(10)
            },
        )
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setStatus(line1: String, line2: String?) {
        if (status.text.toString() != line1) status.text = line1
        if (line2.isNullOrBlank()) {
            status2.visibility = View.GONE
        } else {
            if (status2.text.toString() != line2) status2.text = line2
            status2.visibility = View.VISIBLE
        }
    }

    fun setItems(items: List<Item>) {
        list.removeAllViews()
        items.forEachIndexed { i, item ->
            val b = makeButton(ctx, item.label, item.color, fullWidth = true)
            b.setOnClickListener { item.onClick() }
            list.addView(
                b,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = ctx.dpi(6)
                },
            )
        }
    }

    fun setListMaxHeight(px: Int) {
        scroll.maxHeightPx = px
    }
}

/**
 * The panel shown while inspecting. It has two tabs the user switches at the top: "Properti" (the
 * scrollable property list plus, when enabled, the live text editor) and "Hierarki" (the node tree). Both
 * read the same selection the controller owns. Below the tabs sit the shared action buttons.
 */
internal class PropertyPanel(private val ctx: Context) {

    enum class Tab { PROPERTIES, HIERARCHY }

    /** Which extra section the "Properti" tab shows for the selected node's text editing. */
    enum class EditMode { HIDDEN, HINT, EDITABLE }

    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = surfaceBackground(ctx)
        val p = ctx.dpi(12)
        setPadding(p, ctx.dpi(10), p, p)
    }

    private val title = makeText(ctx, 15f, OverlayColors.TEXT, bold = true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val subtitle = makeText(ctx, 12f, OverlayColors.MUTED).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.MIDDLE
        visibility = View.GONE
    }
    private val collapseBtn = TextView(ctx).apply {
        text = "▾"
        setTextColor(OverlayColors.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        background = buttonBackground(ctx, OverlayColors.BUTTON)
        contentDescription = "Ciutkan panel"
        isClickable = true
    }

    // ---- tabs ----
    private val tabPropBtn = makeButton(ctx, "Properti", OverlayColors.BLUE, fullWidth = false)
    private val tabTreeBtn = makeButton(ctx, "Hierarki", OverlayColors.BUTTON, fullWidth = false)
    private val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    // ---- properties view ----
    private val propScroll = MaxHeightScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val propContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val body = makeText(ctx, 13f, OverlayColors.TEXT).apply {
        setLineSpacing(ctx.dpf(2f), 1f)
    }

    // ---- live text editor (opt-in; only for editable nodes) ----
    private val editTitle = makeText(ctx, 13f, OverlayColors.TEXT, bold = true).apply {
        text = "Edit teks"
        visibility = View.GONE
    }
    val editText = EditText(ctx).apply {
        hint = "Teks baru…"
        setTextColor(OverlayColors.TEXT)
        setHintTextColor(OverlayColors.MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = roundedRect(OverlayColors.NEAR_BLACK, ctx.dpf(8f), ctx.dpi(1), OverlayColors.SURFACE_STROKE)
        setPadding(ctx.dpi(10), ctx.dpi(8), ctx.dpi(10), ctx.dpi(8))
        isSingleLine = true
        isFocusableInTouchMode = true
        // Privacy: no suggestions, no autofill and no personalised IME learning of what is typed here.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        visibility = View.GONE
    }
    val applyBtn = makeButton(ctx, "Terapkan", OverlayColors.GREEN, fullWidth = false)
    val clearBtn = makeButton(ctx, "Kosongkan", OverlayColors.BUTTON, fullWidth = false)
    private val editButtons = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
    }
    private val editHint = makeText(ctx, 12f, OverlayColors.MUTED).apply {
        text = EDIT_HINT
        maxLines = 2
        visibility = View.GONE
    }
    private val editSection = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }

    // ---- hierarchy view ----
    val hierarchyView = HierarchyPanelView(ctx).apply { visibility = View.GONE }

    private val contentFrame = FrameLayout(ctx)

    val parentBtn = makeButton(ctx, "⬆ Induk", OverlayColors.BUTTON, fullWidth = false)
    val childBtn = makeButton(ctx, "⬇ Anak", OverlayColors.BUTTON, fullWidth = false)
    val copyBtn = makeButton(ctx, "Salin", OverlayColors.BUTTON, fullWidth = false)
    val filterBtn = makeButton(ctx, "Filter klik", OverlayColors.BUTTON, fullWidth = false)
    val refreshBtn = makeButton(ctx, "Segarkan", OverlayColors.BUTTON, fullWidth = false)
    val saveBtn = makeButton(ctx, "💾 Simpan layar", OverlayColors.BUTTON, fullWidth = false)
    val doneBtn = makeButton(ctx, "Selesai", OverlayColors.BLUE, fullWidth = false)

    var collapsed = false
        private set

    var activeTab = Tab.PROPERTIES
        private set

    private var editMode = EditMode.HIDDEN

    /** Invoked when the user switches tabs. */
    var onTabChanged: ((Tab) -> Unit)? = null

    /** Invoked when the text field gains focus (so the panel window can accept the soft keyboard). */
    var onEditFocused: ((EditText) -> Unit)? = null

    /** Invoked when the panel is collapsed or expanded. */
    var onCollapseChanged: (() -> Unit)? = null

    private var filterActive = false

    init {
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titles.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        header.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            collapseBtn,
            LinearLayout.LayoutParams(ctx.dpi(36), ctx.dpi(36)).apply { marginStart = ctx.dpi(8) },
        )
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        tabRow.addView(tabPropBtn, LinearLayout.LayoutParams(0, ctx.dpi(38), 1f))
        tabRow.addView(tabTreeBtn, LinearLayout.LayoutParams(0, ctx.dpi(38), 1f).apply { marginStart = ctx.dpi(6) })
        root.addView(
            tabRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(10)
            },
        )

        // Edit section: title, field, buttons, and (alternatively) the opt-in hint.
        editButtons.addView(applyBtn, LinearLayout.LayoutParams(0, ctx.dpi(40), 1f))
        editButtons.addView(clearBtn, LinearLayout.LayoutParams(0, ctx.dpi(40), 1f).apply { marginStart = ctx.dpi(6) })
        editSection.addView(
            editTitle,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(10)
            },
        )
        editSection.addView(
            editText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(6)
            },
        )
        editSection.addView(
            editButtons,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(8)
            },
        )
        editSection.addView(
            editHint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(6)
            },
        )

        propContent.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        propContent.addView(editSection, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        propScroll.addView(propContent, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contentFrame.addView(propScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentFrame.addView(hierarchyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(
            contentFrame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dpi(8)
            },
        )

        root.addView(buttonRow(parentBtn, childBtn, copyBtn, filterBtn), rowParams())
        root.addView(buttonRow(refreshBtn, saveBtn, doneBtn), rowParams())

        collapseBtn.setOnClickListener { setCollapsed(!collapsed) }
        tabPropBtn.setOnClickListener { selectTab(Tab.PROPERTIES) }
        tabTreeBtn.setOnClickListener { selectTab(Tab.HIERARCHY) }
        editText.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onEditFocused?.invoke(editText) }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyBtn.performClick()
                true
            } else {
                false
            }
        }
        applyTabStyles()
    }

    private fun rowParams() =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = ctx.dpi(8)
        }

    private fun buttonRow(vararg buttons: TextView): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, b ->
            row.addView(
                b,
                LinearLayout.LayoutParams(0, ctx.dpi(40), 1f).apply {
                    if (i > 0) marginStart = ctx.dpi(6)
                },
            )
        }
        return row
    }

    private fun applyTabStyles() {
        setButtonColor(ctx, tabPropBtn, if (activeTab == Tab.PROPERTIES) OverlayColors.BLUE else OverlayColors.BUTTON)
        setButtonColor(ctx, tabTreeBtn, if (activeTab == Tab.HIERARCHY) OverlayColors.BLUE else OverlayColors.BUTTON)
    }

    fun selectTab(tab: Tab) {
        if (activeTab == tab) return
        activeTab = tab
        applyTabStyles()
        propScroll.visibility = if (tab == Tab.PROPERTIES) View.VISIBLE else View.GONE
        hierarchyView.visibility = if (tab == Tab.HIERARCHY) View.VISIBLE else View.GONE
        onTabChanged?.invoke(tab)
    }

    fun setContent(titleText: CharSequence, subtitleText: CharSequence?, bodyText: CharSequence, hasSelection: Boolean) {
        title.text = titleText
        if (subtitleText.isNullOrEmpty()) {
            subtitle.visibility = View.GONE
        } else {
            subtitle.text = subtitleText
            subtitle.visibility = View.VISIBLE
        }
        body.text = bodyText
        setButtonEnabled(parentBtn, hasSelection)
        setButtonEnabled(childBtn, hasSelection)
        setButtonEnabled(copyBtn, hasSelection)
    }

    fun editableActive(): Boolean = editMode == EditMode.EDITABLE

    fun setEditMode(mode: EditMode) {
        if (editMode == mode) return
        editMode = mode
        val editable = mode == EditMode.EDITABLE
        editSection.visibility = if (mode == EditMode.HIDDEN) View.GONE else View.VISIBLE
        editTitle.visibility = if (editable) View.VISIBLE else View.GONE
        editText.visibility = if (editable) View.VISIBLE else View.GONE
        editButtons.visibility = if (editable) View.VISIBLE else View.GONE
        editHint.visibility = if (mode == EditMode.HINT) View.VISIBLE else View.GONE
        if (!editable) editText.clearFocus()
    }

    /** Current field text. The caller must not store or log it. */
    fun editTextValue(): String = editText.text?.toString() ?: ""

    fun clearEditText() {
        editText.setText("")
    }

    fun blurEdit() {
        editText.clearFocus()
    }

    fun scrollBodyToTop() {
        propScroll.scrollTo(0, 0)
    }

    fun setFilterActive(active: Boolean) {
        if (filterActive == active) return
        filterActive = active
        filterBtn.text = if (active) "✓ Filter klik" else "Filter klik"
        setButtonColor(ctx, filterBtn, if (active) OverlayColors.GREEN else OverlayColors.BUTTON)
    }

    fun setBodyMaxHeight(px: Int) {
        propScroll.maxHeightPx = px
        hierarchyView.maxHeightPx = px
    }

    fun setCollapsed(value: Boolean) {
        if (collapsed == value) return
        collapsed = value
        tabRow.visibility = if (value) View.GONE else View.VISIBLE
        contentFrame.visibility = if (value) View.GONE else View.VISIBLE
        collapseBtn.text = if (value) "▴" else "▾"
        collapseBtn.contentDescription = if (value) "Bentangkan panel" else "Ciutkan panel"
        onCollapseChanged?.invoke()
    }

    private companion object {
        const val EDIT_HINT = "Aktifkan \"edit teks\" di Pengaturan untuk mengisi kolom ini"
    }
}
